package lodgebilling.model;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import lodgebilling.dao.AuditLogDao;
import lodgebilling.dao.BillDao;
import lodgebilling.model.security.Permission;
import lodgebilling.model.security.Session;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
        audit.record(session.username(), AuditAction.BILL_SAVED,
                bill.getReceiptNumber() + " for " + bill.getGuestName() + ", total " + Money.format(bill.getTotal()));
        return bill;
    }

    /**
     * Puts the bookings and walk-ins in the list beside the saved bills, newest last.
     * They are re-read each time, so their payments and charges are up to date.
     */
    public void showStays(List<Bill> stays) {
        List<Bill> rows = new ArrayList<>(bills.stream().filter(b -> !b.isStay()).toList());
        rows.addAll(stays);
        rows.sort(Comparator.comparing(Bill::getIssuedAt));
        bills.setAll(rows);
    }

    /** Swaps in the new figures of one receipt (e.g. after a payment), keeping its place in the list. */
    public void replace(Bill updated) {
        for (int i = 0; i < bills.size(); i++) {
            if (bills.get(i).sameReceipt(updated)) {
                bills.set(i, updated);
                return;
            }
        }
    }

    /**
     * A later payment on a saved quick bill, e.g. the guest paid half up front and now pays the rest.
     * Refused if it is more than the balance (stops typing 10000 for 1000).
     */
    public Bill recordPayment(Bill bill, PaymentMethod method, BigDecimal amount) throws ValidationException {
        session.require(Permission.CREATE_BILL);
        if (bill.isStay()) {
            throw new IllegalArgumentException("Payments on a stay go to its folio");
        }
        if (bill.getBalance().signum() <= 0) {
            throw new ValidationException(bill.getReceiptNumber() + " is already paid in full.", FormField.PAID);
        }
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("Enter an amount above zero.", FormField.PAID);
        }
        if (amount.compareTo(bill.getBalance()) > 0) {
            throw new ValidationException(bill.getGuestName() + " owes only "
                    + Money.format(bill.getBalance()) + ".", FormField.PAID);
        }
        Bill updated = bill.withPayment(amount);
        billDao.addPayment(bill.getReceiptNumber(), method, amount, LocalDateTime.now(), session.username());
        replace(updated);
        audit.record(session.username(), AuditAction.PAYMENT_RECORDED, bill.getReceiptNumber() + " "
                + Money.format(amount) + " from " + bill.getGuestName() + ", balance " + Money.format(updated.getBalance()));
        return updated;
    }

    public void delete(Bill bill) {
        session.require(Permission.DELETE_BILL);
        billDao.delete(bill.getReceiptNumber());
        bills.remove(bill);
        audit.record(session.username(), AuditAction.BILL_DELETED,
                bill.getReceiptNumber() + " for " + bill.getGuestName() + ", total " + Money.format(bill.getTotal()));
    }

}
