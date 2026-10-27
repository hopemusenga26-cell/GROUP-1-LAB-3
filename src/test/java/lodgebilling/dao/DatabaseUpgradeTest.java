package lodgebilling.dao;

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
}
