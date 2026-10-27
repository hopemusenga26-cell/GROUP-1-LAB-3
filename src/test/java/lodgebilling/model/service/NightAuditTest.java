package lodgebilling.model.service;

import lodgebilling.dao.Database;
import lodgebilling.model.NightAudit;
import lodgebilling.model.Payer;
import lodgebilling.model.PaymentMethod;
import lodgebilling.model.Reservation;
import lodgebilling.model.ReservationForm;
import lodgebilling.model.ReservationStatus;
import lodgebilling.model.Room;
import lodgebilling.model.RoomStatus;
import lodgebilling.model.User;
import lodgebilling.model.security.AccessDeniedException;
import lodgebilling.model.security.Role;
import lodgebilling.model.security.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The night audit, seen on the evening of 14 Oct after bookings made on 12 Oct.
 * Two services share one database: one with the clock on the 12th to set things up,
 * one on the 14th to run the audit, as if the app had been used over three days.
 */
class NightAuditTest {

    private static final Session DESK = new Session(new User(2, "mary", "Mary", Role.STAFF, true, false));
    private static final Session CLEANER = new Session(new User(3, "joe", "Joe", Role.HOUSEKEEPING, true, false));
    private static final LocalDate D12 = LocalDate.of(2026, 10, 12);
    private static final LocalDate D14 = LocalDate.of(2026, 10, 14);

    private Database db;
    private LodgeService on12;
    private LodgeService on14;

    private Reservation noShow;
    private Reservation arrivingToday;
    private Reservation leavingToday;
    private Reservation overstayed;

    private static Clock at(LocalDate day) {
        return Clock.fixed(day.atTime(20, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    private Room room(LodgeService s, String number) {
        return s.rooms().stream().filter(r -> r.number().equals(number)).findFirst().orElseThrow();
    }

    private ReservationForm form(String guest, String roomNo, LocalDate in, LocalDate out) {
        return new ReservationForm(guest, "+260 97 123 4567", "", false, room(on12, roomNo), in, out, "");
    }

    @BeforeEach
    void setUp() throws LodgeException {
        db = Database.inMemory();
        on12 = LodgeServiceTest.lodgeService(db, at(D12));
        on14 = LodgeServiceTest.lodgeService(db, at(D14));

        noShow = on12.book(DESK, form("Never Came", "101", D12.plusDays(1), D12.plusDays(4)));        // due the 13th
        arrivingToday = on12.book(DESK, form("Late Arrival", "102", D14, D14.plusDays(2)));
        leavingToday = on12.walkIn(DESK, form("Due Out", "201", D12, D14));
        overstayed = on12.walkIn(DESK, form("Stayed On", "202", D12, D12.plusDays(1)));            // due out the 13th
        on12.book(DESK, form("Tomorrow Guest", "301", D14.plusDays(1), D14.plusDays(2)));
        on12.setRoomStatus(DESK, room(on12, "301"), RoomStatus.DIRTY);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private static List<Long> ids(List<Reservation> stays) {
        return stays.stream().map(Reservation::id).toList();
    }

    @Test
    void theAuditFindsEverythingThatNeedsSortingOut() {
        NightAudit audit = on14.nightAudit(DESK);
        assertEquals(D14, audit.date());
        assertEquals(List.of(noShow.id()), ids(audit.overdueArrivals()));
        assertEquals(List.of(arrivingToday.id()), ids(audit.arrivingToday()));
        assertEquals(List.of(leavingToday.id()), ids(audit.dueOut()));
        assertEquals(List.of(overstayed.id()), ids(audit.overstays()));
        assertEquals(List.of("301"), audit.roomsNotReady().stream().map(Room::number).toList());
        assertEquals(4, audit.openItems());   // today's expected arrival is only a reminder
        assertFalse(audit.dayClosed());
    }

    @Test
    void markingNoShowsFreesTheRoomAndChargesNothing() throws LodgeException {
        assertEquals(1, on14.markOverdueNoShows(DESK));

        assertEquals(ReservationStatus.NO_SHOW, on14.reservation(noShow.id()).status());
        assertTrue(on14.freeRooms(D14, D14.plusDays(1), 0).stream().anyMatch(r -> r.number().equals("101")));
        assertTrue(on14.nightAudit(DESK).overdueArrivals().isEmpty());
        assertTrue(on14.outstanding(new Session(new User(1, "boss", "Boss", Role.ADMIN, true, false)))
                .stream().noneMatch(f -> f.stay().id() == noShow.id()));
        // A no-show can take no more money, and is not shown on the tape chart
        assertThrows(LodgeException.class, () -> on14.recordPayment(DESK, noShow.id(), PaymentMethod.CASH,
                BigDecimal.TEN, Payer.GUEST, ""));
        assertTrue(on14.reservationsBetween(D12, D14.plusDays(3)).stream().noneMatch(r -> r.id() == noShow.id()));
    }

    @Test
    void aGuestWhoIsNotDueYetCannotBeANoShow() throws LodgeException {
        Reservation later = on14.book(DESK, form("Next Week", "302", D14.plusDays(7), D14.plusDays(8)));
        assertThrows(LodgeException.class, () -> on14.markNoShow(DESK, later.id()));
        assertThrows(LodgeException.class, () -> on14.markNoShow(DESK, leavingToday.id()));   // already in house
        on14.markNoShow(DESK, arrivingToday.id());   // reception may know tonight that they are not coming
        assertEquals(ReservationStatus.NO_SHOW, on14.reservation(arrivingToday.id()).status());
    }

    @Test
    void housekeepingCannotRunTheAudit() {
        assertThrows(AccessDeniedException.class, () -> on14.nightAudit(CLEANER));
        assertThrows(AccessDeniedException.class, () -> on14.markOverdueNoShows(CLEANER));
    }
}
