package lodgebilling.model.service;

import lodgebilling.dao.Database;
import lodgebilling.model.ReservationForm;
import lodgebilling.model.Room;
import lodgebilling.model.RoomStatus;
import lodgebilling.model.RoomType;
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
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rooms, housekeeping and prices: who may change what, and the rules. */
class RoomServiceTest {

    private static final Session ADMIN = new Session(new User(1, "boss", "Boss", Role.ADMIN, true, false));
    private static final Session DESK = new Session(new User(2, "mary", "Mary", Role.STAFF, true, false));
    private static final Session CLEANER = new Session(new User(3, "joe", "Joe", Role.HOUSEKEEPING, true, false));

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

    @Test
    void firstRunSeedsNineAvailableRooms() {
        assertEquals(9, lodge.rooms().size());
        assertEquals(RoomType.FAMILY, room("302").type());
        assertEquals(9, lodge.rooms().stream().filter(r -> r.status() == RoomStatus.AVAILABLE).count());
    }

    @Test
    void housekeepingCanMarkRoomsDirtyAndClean() throws LodgeException {
        lodge.setRoomStatus(CLEANER, room("101"), RoomStatus.DIRTY);
        assertEquals(RoomStatus.DIRTY, room("101").status());
        lodge.setRoomStatus(CLEANER, room("101"), RoomStatus.AVAILABLE);
        assertEquals(RoomStatus.AVAILABLE, room("101").status());
    }

    @Test
    void occupiedIsNeverSetByHand() {
        assertThrows(LodgeException.class, () -> lodge.setRoomStatus(DESK, room("101"), RoomStatus.OCCUPIED));
    }

    @Test
    void onlyAnAdminAddsRoomsAndNumbersMustBeNew() throws LodgeException {
        assertThrows(AccessDeniedException.class, () -> lodge.addRoom(DESK, "105", RoomType.STANDARD));
        assertThrows(AccessDeniedException.class, () -> lodge.addRoom(CLEANER, "105", RoomType.STANDARD));
        lodge.addRoom(ADMIN, "105", RoomType.STANDARD);
        assertEquals(10, lodge.rooms().size());
        assertThrows(LodgeException.class, () -> lodge.addRoom(ADMIN, "105", RoomType.DELUXE));
        assertThrows(LodgeException.class, () -> lodge.addRoom(ADMIN, "1 0 5", RoomType.DELUXE));
    }

    @Test
    void anAdminCanRenumberARoomAndChangeItsType() throws LodgeException {
        assertThrows(AccessDeniedException.class, () -> lodge.updateRoom(DESK, room("101"), "105", RoomType.DELUXE));
        lodge.updateRoom(ADMIN, room("101"), "105", RoomType.DELUXE);
        assertEquals(RoomType.DELUXE, room("105").type());
        assertTrue(lodge.rooms().stream().noneMatch(r -> r.number().equals("101")));
        // Keeping its own number is fine; taking another room's is not
        lodge.updateRoom(ADMIN, room("105"), "105", RoomType.FAMILY);
        assertThrows(LodgeException.class, () -> lodge.updateRoom(ADMIN, room("105"), "102", RoomType.FAMILY));
    }

    @Test
    void onlyARoomThatWasNeverBookedCanBeDeleted() throws LodgeException {
        assertThrows(AccessDeniedException.class, () -> lodge.deleteRoom(DESK, room("102")));
        lodge.deleteRoom(ADMIN, room("102"));
        assertEquals(8, lodge.rooms().size());

        lodge.book(DESK, new ReservationForm("Ann Banda", "", "", false, room("103"),
                LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 22), ""));
        assertThrows(LodgeException.class, () -> lodge.deleteRoom(ADMIN, room("103")));
        assertEquals(8, lodge.rooms().size());
    }

    @Test
    void firstRunSeedsTheOriginalPricesAndSixteenPercentVat() {
        assertEquals(new BigDecimal("550.00"), lodge.rateCard().rateFor(RoomType.DELUXE));
        assertEquals(0, new BigDecimal("16").compareTo(lodge.rateCard().vatPercent()));
    }

    @Test
    void onlyAnAdminChangesPricesAndTheyAreKeptAfterRestart() throws LodgeException {
        Map<RoomType, BigDecimal> rates = new EnumMap<>(RoomType.class);
        rates.put(RoomType.STANDARD, new BigDecimal("400"));
        rates.put(RoomType.DELUXE, new BigDecimal("600"));
        rates.put(RoomType.FAMILY, new BigDecimal("900"));
        assertThrows(AccessDeniedException.class, () -> lodge.saveRates(DESK, rates, BigDecimal.TEN));
        lodge.saveRates(ADMIN, rates, BigDecimal.TEN);

        // A new service on the same database, as after restarting the app
        LodgeService restarted = LodgeServiceTest.lodgeService(db, Clock.systemUTC());
        assertEquals(new BigDecimal("600.00"), restarted.rateCard().rateFor(RoomType.DELUXE));
        assertEquals(0, BigDecimal.TEN.compareTo(restarted.rateCard().vatPercent()));
    }

    @Test
    void badPricesAreRefused() {
        Map<RoomType, BigDecimal> rates = new EnumMap<>(RoomType.class);
        rates.put(RoomType.STANDARD, BigDecimal.ZERO);
        rates.put(RoomType.DELUXE, new BigDecimal("600"));
        rates.put(RoomType.FAMILY, new BigDecimal("900"));
        assertThrows(LodgeException.class, () -> lodge.saveRates(ADMIN, rates, BigDecimal.TEN));
        rates.put(RoomType.STANDARD, new BigDecimal("400"));
        assertThrows(LodgeException.class, () -> lodge.saveRates(ADMIN, rates, new BigDecimal("75")));
    }
}
