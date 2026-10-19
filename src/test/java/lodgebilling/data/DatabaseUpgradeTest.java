package lodgebilling.data;

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
            UserRepository users = new UserRepository(db);
            User boss = users.findAll().get(0);
            assertEquals("Old Boss", boss.fullName());
            assertNull(boss.lastLogin());
            users.recordSuccessfulLogin(boss.id(), at);
        }
        // Opening it a second time must not try to add the column again
        try (Database db = Database.open(file)) {
            assertEquals(at, new UserRepository(db).findAll().get(0).lastLogin());
        }
    }
}
