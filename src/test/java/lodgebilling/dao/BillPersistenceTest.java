package lodgebilling.dao;

import lodgebilling.model.AuditAction;
import lodgebilling.model.Bill;
import lodgebilling.model.BillInput;
import lodgebilling.model.BillingModel;
import lodgebilling.model.DailyStat;
import lodgebilling.model.PaymentMethod;
import lodgebilling.model.RoomType;
import lodgebilling.model.Theme;
import lodgebilling.model.User;
import lodgebilling.model.ValidationException;
import lodgebilling.model.security.AccessDeniedException;
import lodgebilling.model.security.Role;
import lodgebilling.model.security.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
    void partPaidBillCanBePaidOffLater() throws ValidationException {
        BillingModel model = new BillingModel(repo, audit, STAFF, Theme.LIGHT);
        Bill bill = model.save(input("John Phiri"));   // total 2812.50, 1000.50 paid
        assertEquals(new BigDecimal("1812.00"), bill.getBalance());

        // More than is owed is refused (a typo), and nothing changes
        assertThrows(ValidationException.class,
                () -> model.recordPayment(bill, PaymentMethod.CASH, new BigDecimal("18120")));

        Bill part = model.recordPayment(bill, PaymentMethod.CASH, new BigDecimal("812"));
        assertEquals(new BigDecimal("1000.00"), part.getBalance());
        Bill done = model.recordPayment(part, PaymentMethod.MOBILE_MONEY, new BigDecimal("1000"));
        assertEquals(new BigDecimal("0.00"), done.getBalance());
        assertEquals(done, model.bills().get(0));   // the table row is replaced, not duplicated
        assertEquals(1, model.bills().size());
        assertThrows(ValidationException.class, () -> model.recordPayment(done, PaymentMethod.CASH, BigDecimal.ONE));

        // Kept after a restart
        assertEquals(new BigDecimal("2812.50"), repo.findAll().get(0).getPaid());
        assertTrue(audit.latest(10).stream().anyMatch(e -> e.action() == AuditAction.PAYMENT_RECORDED));
    }

    @Test
    void laterPaymentCountsOnTheDayItWasPaid() {
        LocalDate issued = LocalDate.of(2026, 10, 1);
        Bill bill = repo.insert(input("John Phiri"), issued.atTime(10, 0), "mary");   // 1000.50 paid that day
        repo.addPayment(bill.getReceiptNumber(), PaymentMethod.CASH, new BigDecimal("812"),
                LocalDateTime.of(2026, 10, 3, 9, 30), "mary");

        List<DailyStat> days = new SqliteReportDao(db).daily(issued, issued.plusDays(2));
        assertEquals(new BigDecimal("1000.50"), days.get(0).paymentsReceived());
        assertEquals(new BigDecimal("0.00"), days.get(1).paymentsReceived());
        assertEquals(new BigDecimal("812.00"), days.get(2).paymentsReceived());   // not added to the 1st
        assertEquals(new BigDecimal("1812.50"), repo.findAll().get(0).getPaid());

        repo.delete(bill.getReceiptNumber());   // its payments go with it
        assertEquals(new BigDecimal("0.00"), new SqliteReportDao(db).daily(issued, issued.plusDays(2))
                .get(2).paymentsReceived());
    }

    @Test
    void guestNameWithQuoteIsStoredSafely() {
        new BillingModel(repo, audit, STAFF, Theme.LIGHT).save(input("Mary O'Neil"));
        assertEquals("Mary O'Neil", repo.findAll().get(0).getGuestName());
    }
}
