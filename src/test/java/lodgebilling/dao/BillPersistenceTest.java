package lodgebilling.dao;

import lodgebilling.model.AuditAction;
import lodgebilling.model.Bill;
import lodgebilling.model.BillInput;
import lodgebilling.model.BillingModel;
import lodgebilling.model.RoomType;
import lodgebilling.model.Theme;
import lodgebilling.model.User;
import lodgebilling.model.security.AccessDeniedException;
import lodgebilling.model.security.Role;
import lodgebilling.model.security.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BillPersistenceTest {

    private static final Session ADMIN = new Session(new User(1, "admin", "Admin", Role.ADMIN, true, false));
    private static final Session STAFF = new Session(new User(2, "mary", "Mary", Role.STAFF, true, false));

    private Database db;
    private BillDao repo;
    private AuditLogDao audit;

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        repo = new SqliteBillDao(db);
        audit = new SqliteAuditLogDao(db, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private static BillInput input(String name) {
        return new BillInput(name, RoomType.DELUXE, new BigDecimal("550"), 5, new BigDecimal("200"), new BigDecimal("1000.50"), "late checkout");
    }

    @Test
    void savedBillsComeBackExactly() {
        Bill saved = new BillingModel(repo, audit, STAFF, Theme.LIGHT).save(input("John Phiri"));

        // A fresh model reads from the database, as after restarting the app
        Bill loaded = new BillingModel(repo, audit, STAFF, Theme.LIGHT).bills().get(0);
        assertEquals(saved.getReceiptNumber(), loaded.getReceiptNumber());
        assertEquals("mary", loaded.getCreatedBy());
        assertEquals(new BigDecimal("1000.50"), loaded.getPaid());   // no floating-point drift
        assertEquals(new BigDecimal("2812.50"), loaded.getTotal());
        assertEquals("late checkout", loaded.getNotes());
    }

    @Test
    void staffCanSaveButNotDelete() {
        BillingModel model = new BillingModel(repo, audit, STAFF, Theme.LIGHT);
        Bill bill = model.save(input("John Phiri"));
        assertThrows(AccessDeniedException.class, () -> model.delete(bill));
        assertEquals(1, repo.findAll().size());
    }

    @Test
    void adminDeleteRemovesFromDatabaseAndNumbersAreNotReused() {
        BillingModel model = new BillingModel(repo, audit, ADMIN, Theme.LIGHT);
        model.save(input("Guest One"));
        Bill second = model.save(input("Guest Two"));
        model.delete(second);
        assertEquals(1, repo.findAll().size());

        Bill third = model.save(input("Guest Three"));
        assertEquals("R003", third.getReceiptNumber());   // R002 is gone for good, not handed out again
        assertTrue(audit.latest(10).stream().anyMatch(e -> e.action() == AuditAction.BILL_DELETED));
    }

    @Test
    void guestNameWithQuoteIsStoredSafely() {
        new BillingModel(repo, audit, STAFF, Theme.LIGHT).save(input("Mary O'Neil"));
        assertEquals("Mary O'Neil", repo.findAll().get(0).getGuestName());
    }
}
