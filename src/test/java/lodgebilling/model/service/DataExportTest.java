package lodgebilling.model.service;

import lodgebilling.controller.DataExporter;
import lodgebilling.dao.Database;
import lodgebilling.model.ExportTable;
import lodgebilling.model.Payer;
import lodgebilling.model.PaymentMethod;
import lodgebilling.model.Reservation;
import lodgebilling.model.ReservationForm;
import lodgebilling.model.Room;
import lodgebilling.model.User;
import lodgebilling.model.security.AccessDeniedException;
import lodgebilling.model.security.Role;
import lodgebilling.model.security.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataExportTest {

    private static final Session ADMIN = new Session(new User(1, "boss", "Boss", Role.ADMIN, true, false));
    private static final Session DESK = new Session(new User(2, "mary", "Mary", Role.STAFF, true, false));
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    private Database db;
    private LodgeService lodge;

    @BeforeEach
    void setUp() throws LodgeException {
        db = Database.inMemory();
        lodge = LodgeServiceTest.lodgeService(db, Clock.fixed(TODAY.atTime(9, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        Reservation john = lodge.walkIn(DESK, form("John M. Phiri", "+260 97 123 4567", "101", TODAY, TODAY.plusDays(2)));
        lodge.recordPayment(DESK, john.id(), PaymentMethod.CASH, new BigDecimal("300"), Payer.GUEST, "");
        lodge.book(DESK, form("John Phiri", "0971234567", "102", TODAY.plusDays(5), TODAY.plusDays(6)));  // same phone
        lodge.book(DESK, form("Mary Banda", "+260 96 555 0000", "201", TODAY.plusDays(1), TODAY.plusDays(3)));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private ReservationForm form(String guest, String phone, String roomNo, LocalDate in, LocalDate out) {
        Room room = lodge.rooms().stream().filter(r -> r.number().equals(roomNo)).findFirst().orElseThrow();
        return new ReservationForm(guest, phone, "", false, room, in, out, "");
    }

    private static ExportTable table(List<ExportTable> tables, String name) {
        return tables.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void theExportHasEveryBookingButNoNamesOrPhoneNumbers() {
        List<ExportTable> tables = lodge.exportTables(ADMIN);
        ExportTable bookings = table(tables, "bookings");
        assertEquals(3, bookings.rows().size());
        assertEquals(1, table(tables, "folio_lines").rows().size());
        assertEquals(1, table(tables, "daily_stats").rows().size());   // from the first check-in to today

        String everything = tables.toString();
        // Whole phone numbers only: a short digit run could appear in a random hash by chance
        for (String secret : List.of("Phiri", "Banda", "971234567", "123 4567", "965550000", "555 0000")) {
            assertFalse(everything.contains(secret), "export leaks " + secret);
        }
    }

    @Test
    void theSameGuestGetsTheSameKeyHoweverThePhoneWasTyped() {
        ExportTable bookings = table(lodge.exportTables(ADMIN), "bookings");
        int key = bookings.header().indexOf("guest_key");
        String john1 = (String) bookings.rows().get(0).get(key);
        String john2 = (String) bookings.rows().get(1).get(key);
        String mary = (String) bookings.rows().get(2).get(key);
        assertEquals(16, john1.length());
        assertEquals(john1, john2);
        assertNotEquals(john1, mary);
        assertEquals(john1, table(lodge.exportTables(ADMIN), "bookings").rows().get(0).get(key));   // stable
    }

    @Test
    void onlyAnAdminCanExport() {
        assertThrows(AccessDeniedException.class, () -> lodge.exportTables(DESK));
    }

    @Test
    void theFolderHasACsvPerTableAndAManifest(@TempDir Path dir) throws Exception {
        Path folder = DataExporter.export(lodge.exportTables(ADMIN), dir, LocalDateTime.of(2026, 10, 12, 21, 30));
        assertEquals("lodge-export-2026-10-12_2130", folder.getFileName().toString());

        List<String> lines = Files.readAllLines(folder.resolve("bookings.csv"), StandardCharsets.UTF_8);
        assertTrue(lines.get(0).startsWith("booking_ref,status,"), "no byte-order mark, plain header");
        assertEquals(4, lines.size());

        String manifest = Files.readString(folder.resolve("manifest.json"));
        assertTrue(manifest.contains("\"format_version\": " + LodgeService.EXPORT_FORMAT_VERSION));
        assertTrue(manifest.contains("{\"file\": \"bookings.csv\", \"rows\": 3"));
        assertTrue(Files.exists(folder.resolve("folio_lines.csv")));
        assertTrue(Files.exists(folder.resolve("daily_stats.csv")));
    }
}
