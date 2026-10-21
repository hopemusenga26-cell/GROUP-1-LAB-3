package lodgebilling.model;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import lodgebilling.dao.AuditLogDao;
import lodgebilling.dao.BillDao;
import lodgebilling.model.security.Permission;
import lodgebilling.model.security.Session;

import java.time.LocalDateTime;

/**
 * The M in MVC: everything the app remembers. It knows nothing about screens.
 * Views observe it (the bills list, the status message, the theme) and the
 * controller changes it in response to what the user does.
 *
 * Bills are now written through to the database. The permission checks live here,
 * next to the data they protect, not in the buttons.
 */
public class BillingModel {

    // Nothing to report: the status bar (shared by every page) stays empty rather than saying "Ready."
    private static final StatusMessage READY = StatusMessage.info("");

    private final BillDao billDao;
    private final AuditLogDao audit;
    private final Session session;

    private final ObservableList<Bill> bills = FXCollections.observableArrayList();
    private final ObjectProperty<StatusMessage> status = new SimpleObjectProperty<>(READY);
    private final ObjectProperty<Theme> theme;

    // The form stays filled after saving, so remember what was last saved
    // to stop the same bill being saved twice
    private BillInput lastSavedInput;
    private String lastSavedReceiptNumber;

    public BillingModel(BillDao billDao, AuditLogDao audit, Session session, Theme initialTheme) {
        this.billDao = billDao;
        this.audit = audit;
        this.session = session;
        this.theme = new SimpleObjectProperty<>(initialTheme);
        bills.setAll(billDao.findAll());
    }

    // ---------- Observable state ----------

    public ObservableList<Bill> bills() {
        return bills;
    }

    public Session session() {
        return session;
    }

    public ObjectProperty<StatusMessage> statusProperty() {
        return status;
    }

    public void setStatus(StatusMessage message) {
        status.set(message);
    }

    /** Back to the opening prompt, e.g. once the user starts fixing an error. */
    public void clearStatus() {
        status.set(READY);
    }

    public ObjectProperty<Theme> themeProperty() {
        return theme;
    }

    public void toggleTheme() {
        theme.set(theme.get().toggled());
    }

    // ---------- Bill operations ----------

    /** Stores and returns a new bill; the database gives it the next number (R001, R002, ...). */
    public Bill save(BillInput input) {
        session.require(Permission.CREATE_BILL);
        Bill bill = billDao.insert(input, LocalDateTime.now(), session.username());
        bills.add(bill);
        lastSavedInput = input;
        lastSavedReceiptNumber = bill.getReceiptNumber();
        audit.record(session.username(), AuditAction.BILL_SAVED,
                bill.getReceiptNumber() + " for " + bill.getGuestName() + ", total " + Money.format(bill.getTotal()));
        return bill;
    }

    public void delete(Bill bill) {
        session.require(Permission.DELETE_BILL);
        billDao.delete(bill.getReceiptNumber());
        bills.remove(bill);
        audit.record(session.username(), AuditAction.BILL_DELETED,
                bill.getReceiptNumber() + " for " + bill.getGuestName() + ", total " + Money.format(bill.getTotal()));
        // If the bill still in the form was deleted, allow it to be saved again
        if (bill.getReceiptNumber().equals(lastSavedReceiptNumber)) {
            forgetLastSaved();
        }
    }

    /** True if this is exactly what was just saved (records compare all their fields). */
    public boolean isRepeatOfLastSave(BillInput input) {
        return input.equals(lastSavedInput);
    }

    public String lastSavedReceiptNumber() {
        return lastSavedReceiptNumber;
    }

    public void forgetLastSaved() {
        lastSavedInput = null;
        lastSavedReceiptNumber = null;
    }
}
