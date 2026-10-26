package lodgebilling.controller;

import javafx.stage.Stage;
import lodgebilling.dao.AuditLogDao;
import lodgebilling.dao.DataException;
import lodgebilling.model.Bill;
import lodgebilling.model.BillingModel;
import lodgebilling.model.Money;
import lodgebilling.model.Payer;
import lodgebilling.model.PaymentLine;
import lodgebilling.model.StatusMessage;
import lodgebilling.model.Theme;
import lodgebilling.model.ValidationException;
import lodgebilling.model.security.AccessDeniedException;
import lodgebilling.model.security.Permission;
import lodgebilling.model.service.AuthService;
import lodgebilling.model.service.LodgeException;
import lodgebilling.model.service.LodgeService;
import lodgebilling.view.MainView;
import lodgebilling.view.PaymentDialog;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The C in MVC: turns what the user does on the receipts list into changes to
 * the model, and tells the view what to do next (dialogs, selection). It never
 * builds widgets, and it holds no data of its own.
 */
public class BillingController implements BillingActions {

    private static final Logger LOG = Logger.getLogger(BillingController.class.getName());

    private final BillingModel model;
    private final Stage stage;
    private final MainView view;
    private final AuthService auth;
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

    @Override
    public void viewReceipt() {
        Bill selected = selectedOrAsk("view it");
        if (selected != null) {
            view.showReceipt(selected);
        }
    }

    @Override
    public void printReceipt() {
        Bill selected = selectedOrAsk("print it");
        if (selected != null) {
            printReceiptFor(selected);
        }
    }

    /** Prints one bill's receipt; used by the toolbar and by the receipt window's own button. */
    @Override
    public void printReceiptFor(Bill bill) {
        if (view.printReceipt(bill)) {
            model.setStatus(StatusMessage.success("Receipt " + bill.getReceiptNumber() + " sent to the printer."));
        }
    }

    /**
     * Takes the rest of a part-paid receipt (or another part of it), in the shared payment
     * window. A booking or walk-in gets the payment on its folio; a quick bill gets a dated
     * payment of its own. Either way the row is updated in place.
     */
    @Override
    public void recordPayment() {
        Bill selected = selectedOrAsk("record a payment on it");
        if (selected == null) {
            return;
        }
        if (selected.getBalance().signum() <= 0) {
            model.setStatus(StatusMessage.info(selected.getReceiptNumber() + " is already paid in full."));
            return;
        }
        try {
            if (selected.isStay()) {
                if (view.pageContext().takePayment(stage, selected.stayId())) {
                    showUpdated(lodge.stayReceipt(selected.stayId()));
                }
                return;
            }
            Bill[] updated = new Bill[1];
            boolean paid = PaymentDialog.show(stage, theme(), "Take payment",
                    selected.getReceiptNumber() + " - " + selected.getGuestName() + ": "
                            + Money.format(selected.getBalance()) + " due",
                    "Record payment",
                    List.of(new PaymentDialog.Due(Payer.GUEST, selected.getGuestName(), selected.getBalance())),
                    lines -> updated[0] = payQuickBill(selected, lines));
            if (paid) {
                showUpdated(updated[0]);
            }
        } catch (LodgeException | AccessDeniedException e) {
            model.setStatus(StatusMessage.error(e.getMessage()));
        } catch (DataException e) {
            LOG.log(Level.SEVERE, "Recording a payment failed", e);
            model.setStatus(StatusMessage.error("Could not record the payment: " + e.getMessage()));
        }
    }

    private Bill payQuickBill(Bill bill, List<PaymentLine> lines) throws LodgeException {
        if (lines.isEmpty()) {
            throw new LodgeException("Enter the amount being paid.");
        }
        PaymentLine line = lines.get(0);
        try {
            return model.recordPayment(bill, line.method(), line.amount());
        } catch (ValidationException e) {
            throw new LodgeException(e.getMessage());
        }
    }

    /** Puts a receipt's new figures in its row, keeps it selected and reports the balance. */
    private void showUpdated(Bill updated) {
        model.replace(updated);
        view.selectBill(updated);
        model.setStatus(StatusMessage.success("Payment recorded for " + updated.getReceiptNumber() + ". "
                + (updated.getBalance().signum() <= 0 ? "Paid in full."
                        : "Balance: " + Money.format(updated.getBalance()) + ".")));
    }

    @Override
    public void deleteSelected() {
        Bill selected = selectedOrAsk("delete it");
        if (selected == null) {
            return;
        }
        if (selected.isStay()) {
            model.setStatus(StatusMessage.error(selected.getReceiptNumber()
                    + " is a booking. Cancel it or void its lines from the Stays page instead."));
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
    public void changePassword() {
        boolean changed = view.changePassword(false,
                (current, replacement) -> auth.changeOwnPassword(model.session(), current, replacement));
        if (changed) {
            model.setStatus(StatusMessage.success("Your password has been changed."));
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

    /** The selected receipt, or null after asking the user to select one. */
    private Bill selectedOrAsk(String purpose) {
        Bill selected = view.selectedBill();
        if (selected == null) {
            model.setStatus(StatusMessage.error("Select a receipt to " + purpose + "."));
            view.focusTable();
        }
        return selected;
    }
}
