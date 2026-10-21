package lodgebilling.controller;

import javafx.stage.Stage;
import lodgebilling.model.Bill;
import lodgebilling.model.BillInput;
import lodgebilling.model.BillValidator;
import lodgebilling.model.BillingModel;
import lodgebilling.model.FormField;
import lodgebilling.model.Money;
import lodgebilling.model.StatusMessage;
import lodgebilling.model.Theme;
import lodgebilling.model.ValidationException;
import lodgebilling.view.MainView;
import lodgebilling.dao.AuditLogDao;
import lodgebilling.dao.DataException;
import lodgebilling.model.security.AccessDeniedException;
import lodgebilling.model.security.Permission;
import lodgebilling.model.service.AuthService;
import lodgebilling.model.service.LodgeService;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The C in MVC: turns what the user does into changes to the model.
 * It reads the form from the view, checks it with the validator, updates the
 * model, and tells the view what to do next (focus, dialogs). It never builds
 * widgets, and it holds no data of its own.
 */
public class BillingController implements BillingActions {

    private final BillingModel model;
    private static final Logger LOG = Logger.getLogger(BillingController.class.getName());
    private static final int AUDIT_ROWS_SHOWN = 500;

    private final BillValidator validator = new BillValidator();
    private final Stage stage;
    private final MainView view;
    private final AuthService auth;
    private final AuditLogDao audit;
    private final Runnable onLogout;
    private final LodgeService lodge;

    /**
     * @param onLogout called after the user logs out, to show the sign-in screen again
     */
    public BillingController(BillingModel model, Stage stage, AuthService auth, AuditLogDao audit,
                             LodgeService lodge, Runnable onLogout) {
        this.lodge = lodge;
        this.model = model;
        this.stage = stage;
        this.auth = auth;
        this.audit = audit;
        this.onLogout = onLogout;
        this.view = new MainView(model, this, stage, lodge, audit, auth);
    }

    public MainView view() {
        return view;
    }

    /** The colour scheme in use, so it can carry over to the next sign-in. */
    public Theme theme() {
        return model.themeProperty().get();
    }

    /**
     * Once the user changes the input an error is about, the error is out of date,
     * so remove it. An error not tied to one input (e.g. "already saved") goes on any edit.
     */
    @Override
    public void formEdited(FormField field) {
        StatusMessage status = model.statusProperty().get();
        if (status.isError() && (status.field() == null || status.field() == field)) {
            model.clearStatus();
        }
    }

    /** Shows the working without saving. */
    @Override
    public void calculate() {
        readForm().ifPresent(input -> {
            Bill b = Bill.of("", input);
            model.setStatus(StatusMessage.info("Room " + Money.format(b.getRoomCharge())
                    + " − discount " + Money.format(b.getDiscount())
                    + " + extras " + Money.format(b.getExtras())
                    + " = total " + Money.format(b.getTotal())
                    + ". Balance: " + Money.format(b.getBalance()) + "."));
        });
    }

    @Override
    public void saveBill() {
        Optional<BillInput> read = readForm();
        if (read.isEmpty()) {
            return;
        }
        BillInput input = read.get();

        if (model.isRepeatOfLastSave(input)) {
            model.setStatus(StatusMessage.error("This receipt is already saved as "
                    + model.lastSavedReceiptNumber()
                    + ". Change the details, or click New guest for the next guest."));
            view.focusField(FormField.NAME);
            return;
        }

        Bill bill;
        try {
            bill = model.save(input);
        } catch (AccessDeniedException e) {
            model.setStatus(StatusMessage.error(e.getMessage()));
            return;
        } catch (DataException e) {
            LOG.log(Level.SEVERE, "Saving a bill failed", e);
            model.setStatus(StatusMessage.error("Could not save the receipt: " + e.getMessage()));
            return;
        }
        view.clearForm();
        model.setStatus(StatusMessage.success(
                "Saved " + bill.getReceiptNumber() + ". Balance: " + Money.format(bill.getBalance())));
        view.scrollToBill(bill);
    }

    @Override
    public void newGuest() {
        model.forgetLastSaved();
        view.clearForm();
        view.clearSelection();
        model.setStatus(StatusMessage.info("Ready for a new guest."));
        view.focusField(FormField.NAME);
    }

    @Override
    public void viewReceipt() {
        Bill selected = view.selectedBill();
        if (selected == null) {
            model.setStatus(StatusMessage.error("Select a receipt to view it."));
            view.focusTable();
            return;
        }
        view.showReceipt(selected);
    }

    @Override
    public void printReceipt() {
        Bill selected = view.selectedBill();
        if (selected == null) {
            model.setStatus(StatusMessage.error("Select a receipt to print it."));
            view.focusTable();
            return;
        }
        printReceiptFor(selected);
    }

    /** Prints one bill's receipt; used by the toolbar/menu and by the receipt window's own button. */
    @Override
    public void printReceiptFor(Bill bill) {
        if (view.printReceipt(bill)) {
            model.setStatus(StatusMessage.success("Receipt " + bill.getReceiptNumber() + " sent to the printer."));
        }
    }

    @Override
    public void deleteSelected() {
        Bill selected = view.selectedBill();
        if (selected == null) {
            model.setStatus(StatusMessage.error("Select a receipt to delete."));
            view.focusTable();
            return;
        }

        // Checked before asking, so staff are not shown a confirmation they cannot act on
        if (!model.session().has(Permission.DELETE_BILL)) {
            model.setStatus(StatusMessage.error("Only an admin can delete receipts."));
            return;
        }
        if (!view.confirmDelete(selected)) {
            model.setStatus(StatusMessage.info("Deletion cancelled."));
            return;
        }
        try {
            model.delete(selected);
        } catch (AccessDeniedException e) {
            model.setStatus(StatusMessage.error(e.getMessage()));
            return;
        } catch (DataException e) {
            LOG.log(Level.SEVERE, "Deleting a bill failed", e);
            model.setStatus(StatusMessage.error("Could not delete the receipt: " + e.getMessage()));
            return;
        }
        view.clearSelection();
        model.setStatus(StatusMessage.success("Receipt " + selected.getReceiptNumber() + " deleted."));
    }

    @Override
    public void toggleTheme() {
        model.toggleTheme();
    }

    @Override
    public void showAbout() {
        view.showAbout();
    }

    @Override
    public void changePassword() {
        boolean changed = view.changePassword(false,
                (current, replacement) -> auth.changeOwnPassword(model.session(), current, replacement));
        if (changed) {
            model.setStatus(StatusMessage.success("Your password has been changed."));
        }
    }

    @Override
    public void manageUsers() {
        if (!model.session().has(Permission.MANAGE_USERS)) {
            model.setStatus(StatusMessage.error("Only an admin can manage users."));
            return;
        }
        view.manageUsers();
    }

    @Override
    public void showAuditLog() {
        if (!model.session().has(Permission.VIEW_AUDIT_LOG)) {
            model.setStatus(StatusMessage.error("Only an admin can read the audit log."));
            return;
        }
        try {
            view.showAuditLog(audit.latest(AUDIT_ROWS_SHOWN));
        } catch (DataException e) {
            LOG.log(Level.SEVERE, "Reading the audit log failed", e);
            model.setStatus(StatusMessage.error("Could not read the audit log: " + e.getMessage()));
        }
    }

    @Override
    public void logout() {
        auth.logout(model.session());
        onLogout.run();
    }

    @Override
    public void exit() {
        stage.close();
    }

    /** Reads and validates the form. On a problem, shows the message and focuses the field. */
    private Optional<BillInput> readForm() {
        try {
            return Optional.of(validator.validate(view.readForm(), lodge.rateCard()));
        } catch (ValidationException e) {
            model.setStatus(StatusMessage.error(e.getMessage(), e.field()));
            view.focusField(e.field());
            return Optional.empty();
        }
    }
}
