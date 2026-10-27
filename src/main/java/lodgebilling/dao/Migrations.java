package lodgebilling.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

/**
 * Numbered, one-way changes to the database schema, the way Flyway or Alembic do it.
 *
 * Each database remembers which changes it already has in a schema_migrations table.
 * On start-up every migration with a higher number is applied, in order, each in its
 * own transaction, and recorded. So a lodge running an old copy of the app can update
 * at any time and end up with exactly the same tables as a fresh install.
 *
 * Rules:
 *  - Never edit or renumber a migration once it is on main: someone's database already has it.
 *  - Never change the CREATE TABLE statements in Database.SCHEMA: that is the frozen
 *    starting point. Every schema change is a new migration at the end of the list.
 *  - Migrations must work on a database with real data in it, not just an empty one.
 */
final class Migrations {

    private static final Logger LOG = Logger.getLogger(Migrations.class.getName());

    /** The SQL work of one migration. Runs inside a transaction. */
    @FunctionalInterface
    interface Work {
        void apply(Statement st) throws SQLException;
    }

    record Migration(int version, String description, Work work) { }

    /** Every schema change since the baseline, oldest first. Append only. */
    static final List<Migration> ALL = List.of(
            new Migration(1, "Add users.last_login_at for the Staff page", st -> {
                // Databases created after the Staff page was added already have it in CREATE TABLE
                if (!hasColumn(st, "users", "last_login_at")) {
                    st.execute("ALTER TABLE users ADD COLUMN last_login_at TEXT");
                }
            }),
            new Migration(2, "Allow the NO_SHOW booking status for the night audit", st -> {
                // SQLite cannot edit a CHECK rule in place, so the table is rebuilt the way the
                // SQLite manual describes: make the new table, copy every row, swap it in.
                st.execute("""
                    CREATE TABLE reservations_new (
                        id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                        guest_name         TEXT    NOT NULL,
                        guest_phone        TEXT    NOT NULL DEFAULT '',
                        company            TEXT    NOT NULL DEFAULT '',
                        company_pays_room  INTEGER NOT NULL DEFAULT 0,
                        room_id            INTEGER NOT NULL REFERENCES rooms(id),
                        check_in           TEXT    NOT NULL,
                        check_out          TEXT    NOT NULL,
                        status             TEXT    NOT NULL DEFAULT 'BOOKED'
                            CHECK (status IN ('BOOKED', 'CHECKED_IN', 'CHECKED_OUT', 'CANCELLED', 'NO_SHOW')),
                        nightly_rate       TEXT    NOT NULL,
                        vat_percent        TEXT    NOT NULL,
                        notes              TEXT    NOT NULL DEFAULT '',
                        created_by         TEXT    NOT NULL,
                        created_at         TEXT    NOT NULL,
                        CHECK (check_out > check_in)
                    )""");
                String columns = "id, guest_name, guest_phone, company, company_pays_room, room_id, check_in, "
                        + "check_out, status, nightly_rate, vat_percent, notes, created_by, created_at";
                st.execute("INSERT INTO reservations_new (" + columns + ") SELECT " + columns + " FROM reservations");
                st.execute("DROP TABLE reservations");
                st.execute("ALTER TABLE reservations_new RENAME TO reservations");
                st.execute("CREATE INDEX IF NOT EXISTS idx_res_room_dates ON reservations(room_id, check_in, check_out)");
            })
    );

    private Migrations() { }

    /** The newest schema this version of the app knows how to use. */
    static int latestVersion() {
        return ALL.isEmpty() ? 0 : ALL.get(ALL.size() - 1).version();
    }

    /** Brings the database up to {@link #latestVersion()}. Safe to call on every start. */
    static void run(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version      INTEGER PRIMARY KEY,
                    description  TEXT NOT NULL,
                    applied_at   TEXT NOT NULL
                )""");
            int current = currentVersion(st);
            if (current > latestVersion()) {
                // Opening it here could quietly undo or corrupt changes this version does not understand
                throw new DataException("This database was last used by a newer version of Lodge Billing "
                        + "(schema " + current + ", this app knows up to " + latestVersion() + "). "
                        + "Update the app (git pull) before opening it.", null);
            }
            for (Migration m : ALL) {
                if (m.version() > current) {
                    apply(c, st, m);
                }
            }
        }
    }

    static int currentVersion(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void apply(Connection c, Statement st, Migration m) throws SQLException {
        // Rebuilding a table (the only way SQLite can change a CHECK rule) briefly drops it, so
        // foreign keys are switched off for the migration and checked again before committing.
        // The PRAGMA only takes effect outside a transaction, hence before setAutoCommit(false).
        st.execute("PRAGMA foreign_keys = OFF");
        c.setAutoCommit(false);
        try {
            m.work().apply(st);
            try (ResultSet broken = st.executeQuery("PRAGMA foreign_key_check")) {
                if (broken.next()) {
                    throw new SQLException("Migration " + m.version() + " left a row in "
                            + broken.getString("table") + " pointing at a missing " + broken.getString("parent"));
                }
            }
            try (var ps = c.prepareStatement(
                    "INSERT INTO schema_migrations (version, description, applied_at) VALUES (?, ?, ?)")) {
                ps.setInt(1, m.version());
                ps.setString(2, m.description());
                ps.setString(3, Instant.now().toString());
                ps.executeUpdate();
            }
            c.commit();
            LOG.info(() -> "Applied database migration " + m.version() + ": " + m.description());
        } catch (SQLException | RuntimeException e) {
            c.rollback();   // all or nothing: a half-applied migration would be worse than none
            throw e;
        } finally {
            c.setAutoCommit(true);
            st.execute("PRAGMA foreign_keys = ON");
        }
    }

    static boolean hasColumn(Statement st, String table, String column) throws SQLException {
        // table is one of our own constants, never user input, so building the SQL here is safe
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }
}
