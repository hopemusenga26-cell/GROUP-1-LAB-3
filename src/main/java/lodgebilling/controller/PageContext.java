package lodgebilling.controller;

import javafx.stage.Stage;
import javafx.stage.Window;
import lodgebilling.dao.AuditLogDao;
import lodgebilling.dao.DataException;
import lodgebilling.model.BillingModel;
import lodgebilling.model.FolioEntry;
import lodgebilling.model.FolioSummary;
import lodgebilling.model.Money;
import lodgebilling.model.Reservation;
import lodgebilling.model.StatusMessage;
import lodgebilling.model.Theme;
import lodgebilling.model.security.AccessDeniedException;
import lodgebilling.model.security.Permission;
import lodgebilling.model.security.Session;
import lodgebilling.model.service.AuthService;
import lodgebilling.model.service.BalanceDueException;
import lodgebilling.model.service.LodgeService;
import lodgebilling.model.service.UserFacingException;
import lodgebilling.view.PaymentDialog;
import lodgebilling.view.ReceiptView;
import lodgebilling.view.Ui;

import java.io.File;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What every page needs: the window, the signed-in user, the services, and one
 * way to report success or failure (the shared status bar, unless a page asks for a step's
 * messages to be shown next to what it acted on).
 */
public class PageContext {

    private static final Logger LOG = Logger.getLogger(PageContext.class.getName());

    /** An action that may be refused with a message. */
    @FunctionalInterface
    public interface Action {
        void run() throws UserFacingException;
    }

    private final Stage owner;
    private final BillingModel model;
    private final LodgeService lodge;
    private final AuditLogDao audit;
    private final AuthService auth;
    private Runnable refreshCurrentPage = () -> { };
    private Consumer<StatusMessage> report;

    public PageContext(Stage owner, BillingModel model, LodgeService lodge, AuditLogDao audit, AuthService auth) {
        this.owner = owner;
        this.model = model;
        this.report = model::setStatus;
        this.lodge = lodge;
        this.audit = audit;
        this.auth = auth;
    }

    public Stage owner()          { return owner; }
    public LodgeService lodge()   { return lodge; }
    public AuditLogDao audit()       { return audit; }
    public AuthService auth()     { return auth; }
    public Session session()      { return model.session(); }
    public Theme theme()          { return model.themeProperty().get(); }
    public boolean can(Permission p) { return model.session().has(p); }

    public void setRefreshCurrentPage(Runnable r) {
        this.refreshCurrentPage = r;
    }

    public void refreshCurrentPage() {
        refreshCurrentPage.run();
    }

    public void success(String text) { report.accept(StatusMessage.success(text)); }
    public void info(String text)    { report.accept(StatusMessage.info(text)); }
    public void error(String text)   { report.accept(StatusMessage.error(text)); }

    /**
     * Runs a step whose messages are shown next to what it acted on (e.g. under a table)
     * instead of in the status bar. Afterwards messages go to the status bar again.
     */
    public void reportingTo(Consumer<StatusMessage> where, Runnable step) {
        Consumer<StatusMessage> before = report;
        report = where;
        try {
            step.run();
        } finally {
            report = before;
        }
    }

    /** Runs an action; a refusal or database problem is shown in the status bar instead of crashing. */
    public boolean attempt(Action action) {
        try {
            action.run();
            return true;
        } catch (UserFacingException | AccessDeniedException e) {
            error(e.getMessage());
        } catch (DataException e) {
            LOG.log(Level.SEVERE, "Database problem", e);
            error("Database problem: " + e.getMessage());
        }
        return false;
    }

    /**
     * Takes a payment on a stay with the shared payment window, filled in with what each
     * payer owes. Used by the folio, the Stays page, the dashboard and the receipts list.
     *
     * @param owner the window the payment window belongs to (e.g. the open folio)
     * @return true if a payment was recorded
     */
    public boolean takePayment(Window owner, long reservationId) {
        if (!can(Permission.POST_FOLIO)) {
            error("Your account cannot take payments.");
            return false;
        }
        LodgeService.Folio[] folio = new LodgeService.Folio[1];
        if (!attempt(() -> folio[0] = lodge.folio(reservationId))) {
            return false;
        }
        Reservation r = folio[0].stay();
        List<PaymentDialog.Due> dues = dues(folio[0].summary());
        if (dues.isEmpty()) {
            info(r.guestName() + " (" + r.reference() + ") has nothing left to pay.");
            return false;
        }
        boolean paid = PaymentDialog.show(owner, theme(), "Take payment",
                r.reference() + " - " + r.guestName() + ": " + Money.format(folio[0].summary().balance()) + " due",
                "Record payment", dues, lines -> lodge.recordPayments(session(), reservationId, lines));
        if (paid) {
            attempt(() -> {
                BigDecimal left = lodge.folio(reservationId).summary().balance();
                success("Payment recorded for " + r.reference() + ". "
                        + (left.signum() > 0 ? "Still owed: " + Money.format(left) + "." : "Paid in full."));
            });
        }
        return paid;
    }

    /** Prints a stay's receipt with its folio's current figures. */
    public void printReceipt(Window owner, long reservationId) {
        attempt(() -> {
            var receipt = lodge.stayReceipt(reservationId);
            if (ReceiptView.print(owner, theme(), receipt)) {
                success("Receipt " + receipt.getReceiptNumber() + " sent to the printer.");
            }
        });
    }

    /** Saves a stay's receipt as a PDF, with its folio's current figures. */
    public void saveReceiptPdf(Window owner, long reservationId) {
        attempt(() -> {
            var receipt = lodge.stayReceipt(reservationId);
            File file = ReceiptView.savePdf(owner, theme(), receipt);
            if (file != null) {
                success("Receipt " + receipt.getReceiptNumber() + " saved as " + file.getName() + ".");
            }
        });
    }

    /**
     * Check-out. If money is still owed, the payment window opens with the amount due,
     * and paying checks the guest out in the same step. Only if that is cancelled is an
     * admin offered a forced check-out (the debt then stays on the folio).
     */
    public void checkOut(Reservation stay) {
        try {
            finishCheckOut(lodge.checkOut(session(), stay.id(), false));
        } catch (BalanceDueException due) {
            if (settleAndCheckOut(stay, due)) {
                refreshCurrentPage();
                return;
            }
            if (!can(Permission.FORCE_CHECKOUT)) {
                info("Check-out cancelled. " + stay.guestName() + " still owes " + Money.format(due.balance()) + ".");
            } else if (Ui.confirm(owner, theme(), "Guest still owes " + Money.format(due.balance()),
                    stay.guestName() + " has not paid in full. Check out anyway? The debt stays on the "
                            + "Outstanding balances report.", "Force check-out")) {
                attempt(() -> finishCheckOut(lodge.checkOut(session(), stay.id(), true)));
            } else {
                info("Check-out cancelled.");
            }
        } catch (UserFacingException | AccessDeniedException e) {
            error(e.getMessage());
        } catch (DataException e) {
            LOG.log(Level.SEVERE, "Database problem", e);
            error("Database problem: " + e.getMessage());
        }
        refreshCurrentPage();
    }

    /** Offers to take what is owed and check out together. True if the guest is now checked out. */
    private boolean settleAndCheckOut(Reservation stay, BalanceDueException due) {
        if (!can(Permission.POST_FOLIO)) {
            return false;
        }
        FolioSummary[] quote = new FolioSummary[1];
        if (!attempt(() -> quote[0] = lodge.checkOutQuote(stay.id()))) {
            return false;
        }
        LodgeService.Folio[] done = new LodgeService.Folio[1];
        boolean paid = PaymentDialog.show(owner, theme(), "Check out " + stay.guestName(),
                stay.guestName() + " owes " + Money.format(due.balance()) + ". Take it now to check out.",
                "Take payment & check out", dues(quote[0]),
                lines -> done[0] = lodge.settleAndCheckOut(session(), stay.id(), lines));
        if (paid) {
            finishCheckOut(done[0]);
        }
        return paid;
    }

    /** One line per payer who still owes something. */
    private static List<PaymentDialog.Due> dues(FolioSummary summary) {
        return summary.payers().stream().filter(p -> p.balance().signum() > 0)
                .map(p -> new PaymentDialog.Due(p.payer(), p.name(), p.balance())).toList();
    }

    private void finishCheckOut(LodgeService.Folio folio) {
        BigDecimal refunded = folio.entries().stream().filter(FolioEntry::isRefund)
                .map(FolioEntry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String room = folio.stay().room().number();
        if (refunded.signum() > 0) {
            Ui.info(owner, theme(), "Give back " + Money.format(refunded),
                    folio.stay().guestName() + " left early and paid for nights not used. Hand back "
                            + Money.format(refunded) + " in cash; it is recorded as a refund on the folio.");
        }
        success(folio.stay().guestName() + " checked out of room " + room + ". Room " + room
                + " is now Dirty until housekeeping cleans it.");
        if (Ui.ask(owner, theme(), "Print the receipt?", "Print " + folio.stay().reference() + " for "
                + folio.stay().guestName() + " to hand over at the desk.", "Print receipt", "Not now")) {
            printReceipt(owner, folio.stay().id());
        }
    }
}
