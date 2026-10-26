package lodgebilling.model.service;

import lodgebilling.dao.Database;
import lodgebilling.model.CashUp;
import lodgebilling.model.ChargeCategory;
import lodgebilling.model.FolioEntry;
import lodgebilling.model.GuestHistory;
import lodgebilling.model.Payer;
import lodgebilling.model.PaymentMethod;
import lodgebilling.model.Reservation;
import lodgebilling.model.ReservationForm;
import lodgebilling.model.ReservationStatus;
import lodgebilling.model.Room;
import lodgebilling.model.User;
import lodgebilling.model.security.AccessDeniedException;
import lodgebilling.model.security.Role;
import lodgebilling.model.security.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Finding stays, recognising returning guests, and the end-of-day cash-up. */
class FrontDeskServiceTest {

    private static final Session ADMIN = new Session(new User(1, "boss", "Boss", Role.ADMIN, true, false));
    private static final Session DESK = new Session(new User(2, "mary", "Mary", Role.STAFF, true, false));
    private static final Session CLEANER = new Session(new User(3, "joe", "Joe", Role.HOUSEKEEPING, true, false));

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    private Database db;
    private LodgeService lodge;

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        Clock clock = Clock.fixed(Instant.parse("2026-10-12T09:00:00Z"), ZoneOffset.UTC);
        lodge = LodgeServiceTest.lodgeService(db, clock);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private Room room(String number) {
        return lodge.rooms().stream().filter(r -> r.number().equals(number)).findFirst().orElseThrow();
    }

    private ReservationForm form(String guest, String phone, String roomNo, LocalDate in, LocalDate out) {
        return new ReservationForm(guest, phone, "", false, room(roomNo), in, out, "");
    }

    private static BigDecimal k(String amount) {
        return new BigDecimal(amount);
    }

    // ---------- Search ----------

    @Test
    void searchFindsAStayByNameBookingNumberRoomOrPhone() throws LodgeException {
        Reservation john = lodge.book(DESK, form("John Phiri", "+260 97 123 4567", "201", TODAY.plusDays(2),
                TODAY.plusDays(4)));
        lodge.book(DESK, form("Mary Banda", "0955 000 111", "101", TODAY.plusDays(2), TODAY.plusDays(3)));

        assertEquals(List.of(john), lodge.search(DESK, "phiri"));
        assertEquals(List.of(john), lodge.search(DESK, john.reference()));
        assertEquals(List.of(john), lodge.search(DESK, "201"));
        // The phone as typed differently from when it was booked
        assertEquals(List.of(john), lodge.search(DESK, "0971234567"));
        // Every word has to match
        assertEquals(List.of(), lodge.search(DESK, "john 101"));
        assertEquals(List.of(), lodge.search(DESK, "   "));
    }

    @Test
    void guestsInHouseAreListedBeforeBookingsStillToCome() throws LodgeException {
        Reservation later = lodge.book(DESK, form("Ann Tembo", "", "101", TODAY.plusDays(5), TODAY.plusDays(6)));
        Reservation in = lodge.walkIn(DESK, form("Ann Mwale", "", "201", TODAY, TODAY.plusDays(1)));

        List<Reservation> found = lodge.search(DESK, "ann");
        assertEquals(in.id(), found.get(0).id());
        assertEquals(later.id(), found.get(1).id());
    }

    @Test
    void housekeepingCannotSearchStays() {
        assertThrows(AccessDeniedException.class, () -> lodge.search(CLEANER, "john"));
        assertThrows(AccessDeniedException.class, () -> lodge.guestHistory(CLEANER, "0971234567"));
    }

    // ---------- Returning guests ----------

    @Test
    void aReturningGuestIsFoundByPhoneWithWhatTheyStillOwe() throws LodgeException {
        // In house and not paid yet: Deluxe 550 + 16% VAT = 638
        lodge.walkIn(DESK, form("John Phiri", "+260 97 123 4567", "201", TODAY, TODAY.plusDays(1)));

        GuestHistory history = lodge.guestHistory(DESK, "097 123 4567");
        assertEquals(1, history.stays().size());
        assertEquals("John Phiri", history.last().guestName());
        assertEquals(0, k("638").compareTo(history.owed()));
    }

    @Test
    void cancelledStaysUnknownAndShortNumbersAreNotAHistory() throws LodgeException {
        Reservation r = lodge.book(DESK, form("John Phiri", "0971234567", "201", TODAY.plusDays(1),
                TODAY.plusDays(2)));
        lodge.cancel(DESK, r.id());

        assertTrue(lodge.guestHistory(DESK, "0971234567").isEmpty());
        assertTrue(lodge.guestHistory(DESK, "0960000000").isEmpty());
        assertTrue(lodge.guestHistory(DESK, "4567").isEmpty());
    }

    // ---------- Cash-up ----------

    /** John in 201 for one night (638), paying 300 in cash and 338 by card. */
    private Reservation paidStay() throws LodgeException {
        Reservation r = lodge.walkIn(DESK, form("John Phiri", "", "201", TODAY, TODAY.plusDays(1)));
        lodge.recordPayment(DESK, r.id(), PaymentMethod.CASH, k("300"), Payer.GUEST, "");
        lodge.recordPayment(DESK, r.id(), PaymentMethod.CARD, k("338"), Payer.GUEST, "");
        return r;
    }

    @Test
    void cashUpTotalsTheDayByStaffAndMethod() throws LodgeException {
        paidStay();

        CashUp day = lodge.cashUp(DESK, TODAY);
        assertFalse(day.isClosed());
        assertEquals(2, day.takings().size());
        assertEquals("mary", day.takings().get(0).staff());
        assertEquals(k("300.00"), day.expectedCash());
        assertEquals(k("338.00"), day.byMethod().get(PaymentMethod.CARD));
        assertEquals(k("638.00"), day.total());
        assertEquals(k("0"), lodge.cashUp(DESK, TODAY.minusDays(1)).total());
    }

    @Test
    void aDrawerThatIsShortOrOverNeedsANote() throws LodgeException {
        paidStay();

        assertThrows(LodgeException.class, () -> lodge.closeDay(DESK, TODAY, k("290"), ""));
        var closing = lodge.closeDay(DESK, TODAY, k("290"), "K10 change given from the drawer");
        assertEquals(k("-10.00"), closing.difference());
        assertTrue(lodge.cashUp(DESK, TODAY).isClosed());
        assertThrows(LodgeException.class, () -> lodge.closeDay(DESK, TODAY, k("290"), "again"));
        assertThrows(LodgeException.class, () -> lodge.closeDay(DESK, TODAY.plusDays(1), k("0"), ""));
    }

    @Test
    void aClosedDayTakesNoPaymentsOrVoidsUntilAnAdminReopensIt() throws LodgeException {
        Reservation r = paidStay();
        lodge.postCharge(DESK, r.id(), ChargeCategory.BAR, "Mosi", k("50"), Payer.GUEST);
        lodge.closeDay(DESK, TODAY, k("300"), "");

        assertThrows(LodgeException.class,
                () -> lodge.recordPayment(DESK, r.id(), PaymentMethod.CASH, k("58"), Payer.GUEST, ""));
        FolioEntry cash = lodge.folio(r.id()).entries().stream()
                .filter(e -> e.kind() == FolioEntry.Kind.PAYMENT).findFirst().orElseThrow();
        assertThrows(LodgeException.class, () -> lodge.voidEntry(ADMIN, cash, "typed the wrong amount"));

        assertThrows(AccessDeniedException.class, () -> lodge.reopenDay(DESK, TODAY));
        lodge.reopenDay(ADMIN, TODAY);
        lodge.recordPayment(DESK, r.id(), PaymentMethod.CASH, k("58"), Payer.GUEST, "");
        assertEquals(k("358.00"), lodge.cashUp(DESK, TODAY).expectedCash());
        assertEquals(ReservationStatus.CHECKED_IN, lodge.reservation(r.id()).status());
    }

    @Test
    void housekeepingCannotSeeTheTakings() {
        assertThrows(AccessDeniedException.class, () -> lodge.cashUp(CLEANER, TODAY));
        assertThrows(AccessDeniedException.class, () -> lodge.closeDay(CLEANER, TODAY, k("0"), ""));
    }
}
