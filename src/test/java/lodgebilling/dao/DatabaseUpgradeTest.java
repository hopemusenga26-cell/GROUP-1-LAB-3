package lodgebilling.dao;

import lodgebilling.model.ReservationStatus;
import lodgebilling.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A database from the previous version must open without losing anyone's account. */
class DatabaseUpgradeTest {

    @Test
    void anOldUsersTableGetsTheLastSignInColumnAndKeepsItsRows(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("old.db");
        // The users table exactly as the previous version created it (no last_login_at)
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL UNIQUE COLLATE NOCASE,
                    full_name TEXT NOT NULL,
                    role TEXT NOT NULL CHECK (role IN ('ADMIN', 'STAFF', 'HOUSEKEEPING')),
                    password_hash TEXT NOT NULL,
                    active INTEGER NOT NULL DEFAULT 1,
                    must_change_password INTEGER NOT NULL DEFAULT 0,
                    failed_attempts INTEGER NOT NULL DEFAULT 0,
                    locked_until TEXT,
                    created_at TEXT NOT NULL)""");
            st.execute("INSERT INTO users (username, full_name, role, password_hash, created_at) "
                    + "VALUES ('boss', 'Old Boss', 'ADMIN', 'x', '2026-10-01T08:00:00Z')");
        }

        Instant at = Instant.parse("2026-10-19T21:30:00Z");
        try (Database db = Database.open(file)) {
            UserDao users = new SqliteUserDao(db);
            User boss = users.findAll().get(0);
            assertEquals("Old Boss", boss.fullName());
            assertNull(boss.lastLogin());
            users.recordSuccessfulLogin(boss.id(), at);
        }
        // Opening it a second time must not try to add the column again
        try (Database db = Database.open(file)) {
            assertEquals(at, new SqliteUserDao(db).findAll().get(0).lastLogin());
        }
    }

    @Test
    void aNewDatabaseIsAtTheLatestSchemaAndReopeningChangesNothing(@TempDir Path dir) {
        Path file = dir.resolve("new.db");
        int version;
        try (Database db = Database.open(file)) {
            version = db.schemaVersion();
            assertEquals(Migrations.latestVersion(), version);
        }
        try (Database db = Database.open(file)) {
            assertEquals(version, db.schemaVersion());
        }
    }

    @Test
    void aDatabaseFromANewerAppIsRefusedInsteadOfDamaged(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("future.db");
        try (Database db = Database.open(file)) {
            assertEquals(Migrations.latestVersion(), db.schemaVersion());
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO schema_migrations VALUES (999, 'from the future', '2030-01-01T00:00:00Z')");
        }
        DataException e = assertThrows(DataException.class, () -> Database.open(file));
        assertTrue(e.getMessage().contains("newer version"), e.getMessage());
    }

    @Test
    void bookingsSurviveTheNoShowMigrationWithTheirFolioLines(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("before-no-show.db");
        // The three tables as they were before migration 2, with a booking and a payment on it
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE rooms (id INTEGER PRIMARY KEY AUTOINCREMENT, number TEXT NOT NULL UNIQUE,
                    room_type TEXT NOT NULL CHECK (room_type IN ('STANDARD', 'DELUXE', 'FAMILY')),
                    status TEXT NOT NULL DEFAULT 'AVAILABLE'
                        CHECK (status IN ('AVAILABLE', 'OCCUPIED', 'DIRTY', 'MAINTENANCE')))""");
            st.execute("""
                CREATE TABLE reservations (id INTEGER PRIMARY KEY AUTOINCREMENT, guest_name TEXT NOT NULL,
                    guest_phone TEXT NOT NULL DEFAULT '', company TEXT NOT NULL DEFAULT '',
                    company_pays_room INTEGER NOT NULL DEFAULT 0, room_id INTEGER NOT NULL REFERENCES rooms(id),
                    check_in TEXT NOT NULL, check_out TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'BOOKED'
                        CHECK (status IN ('BOOKED', 'CHECKED_IN', 'CHECKED_OUT', 'CANCELLED')),
                    nightly_rate TEXT NOT NULL, vat_percent TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '',
                    created_by TEXT NOT NULL, created_at TEXT NOT NULL, CHECK (check_out > check_in))""");
            st.execute("""
                CREATE TABLE folio_entries (id INTEGER PRIMARY KEY AUTOINCREMENT,
                    reservation_id INTEGER NOT NULL REFERENCES reservations(id),
                    kind TEXT NOT NULL CHECK (kind IN ('CHARGE', 'PAYMENT', 'REFUND')), category TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '', amount TEXT NOT NULL,
                    payer TEXT NOT NULL CHECK (payer IN ('GUEST', 'COMPANY')), posted_at TEXT NOT NULL,
                    posted_by TEXT NOT NULL, voided INTEGER NOT NULL DEFAULT 0, void_reason TEXT NOT NULL DEFAULT '')""");
            st.execute("INSERT INTO rooms (number, room_type) VALUES ('101', 'STANDARD')");
            st.execute("INSERT INTO reservations (id, guest_name, room_id, check_in, check_out, status, nightly_rate, "
                    + "vat_percent, created_by, created_at) VALUES (41, 'John M. Phiri', 1, '2026-10-13', '2026-10-15', "
                    + "'BOOKED', '350.00', '16', 'mary', '2026-10-12T09:00')");
            st.execute("INSERT INTO folio_entries (reservation_id, kind, category, amount, payer, posted_at, posted_by) "
                    + "VALUES (41, 'PAYMENT', 'CASH', '200.00', 'GUEST', '2026-10-12T09:05', 'mary')");
        }

        try (Database db = Database.open(file)) {
            assertEquals(Migrations.latestVersion(), db.schemaVersion());
            SqliteReservationDao reservations = new SqliteReservationDao(db);
            assertEquals("John M. Phiri", reservations.findById(41).orElseThrow().guestName());
            assertEquals(1, new SqliteFolioDao(db).findForReservation(41).size());   // still linked to the booking

            reservations.setStatus(41, ReservationStatus.NO_SHOW);   // refused by the old CHECK rule
            assertEquals(ReservationStatus.NO_SHOW, reservations.findById(41).orElseThrow().status());
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT seq FROM sqlite_sequence WHERE name = 'reservations'")) {
            assertTrue(rs.next());
            assertEquals(41, rs.getInt(1));   // new bookings carry on from BK0042, never reusing a number
        }
    }
}
