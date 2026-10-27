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
