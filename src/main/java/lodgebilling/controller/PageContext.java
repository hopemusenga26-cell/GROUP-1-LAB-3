package lodgebilling.controller;

import javafx.stage.Stage;
import lodgebilling.dao.AuditLogDao;
import lodgebilling.dao.DataException;
import lodgebilling.model.BillingModel;
import lodgebilling.model.FolioEntry;
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
import lodgebilling.view.Ui;

import java.math.BigDecimal;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What every page needs: the window, the signed-in user, the services, and one
 * way to report success or failure (the shared status bar).
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

    public PageContext(Stage owner, BillingModel model, LodgeService lodge, AuditLogDao audit, AuthService auth) {
        this.owner = owner;
        this.model = model;
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

    public void success(String text) { model.setStatus(StatusMessage.success(text)); }
    public void info(String text)    { model.setStatus(StatusMessage.info(text)); }
    public void error(String text)   { model.setStatus(StatusMessage.error(text)); }

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
     * Check-out with the safety net from the spec: if money is owed, staff are
     * stopped; an admin is asked to confirm a forced check-out.
     */
    public void checkOut(Reservation stay) {
        try {
            finishCheckOut(lodge.checkOut(session(), stay.id(), false));
        } catch (BalanceDueException due) {
            if (!can(Permission.FORCE_CHECKOUT)) {
                error(due.getMessage() + " (Only an admin can check out a guest who still owes money.)");
                return;
            }
            boolean force = Ui.confirm(owner, theme(), "Guest still owes " + Money.format(due.balance()),
                    stay.guestName() + " has not paid in full. Check out anyway? The debt stays on the "
                            + "Outstanding balances report.", "Force check-out");
            if (force) {
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
    }
}
