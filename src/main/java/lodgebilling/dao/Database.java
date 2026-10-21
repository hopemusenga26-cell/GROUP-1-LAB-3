package lodgebilling.dao;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Owns the single SQLite connection and creates the tables on first run.
 * A desktop app with one user at a time needs no connection pool.
 */
public final class Database implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Database.class.getName());

    // Money is stored as TEXT (BigDecimal.toPlainString) so no amount is ever
    // rounded by floating point. Timestamps are ISO-8601 TEXT, which sorts correctly.
    private static final String[] SCHEMA = {
        """
        CREATE TABLE IF NOT EXISTS users (
            id                   INTEGER PRIMARY KEY AUTOINCREMENT,
            username             TEXT    NOT NULL UNIQUE COLLATE NOCASE,
            full_name            TEXT    NOT NULL,
            role                 TEXT    NOT NULL CHECK (role IN ('ADMIN', 'STAFF', 'HOUSEKEEPING')),
            password_hash        TEXT    NOT NULL,
            active               INTEGER NOT NULL DEFAULT 1,
            must_change_password INTEGER NOT NULL DEFAULT 0,
            failed_attempts      INTEGER NOT NULL DEFAULT 0,
            locked_until         TEXT,
            created_at           TEXT    NOT NULL,
            last_login_at        TEXT
        )""",
        """
        CREATE TABLE IF NOT EXISTS bills (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            guest_name  TEXT    NOT NULL,
            room_type   TEXT    NOT NULL,
            nights      INTEGER NOT NULL CHECK (nights > 0),
            nightly_rate TEXT   NOT NULL,
            extras      TEXT    NOT NULL,
            paid        TEXT    NOT NULL,
            notes       TEXT    NOT NULL DEFAULT '',
            issued_at   TEXT    NOT NULL,
            created_by  TEXT    NOT NULL
        )""",
        """
        CREATE TABLE IF NOT EXISTS audit_log (
            id        INTEGER PRIMARY KEY AUTOINCREMENT,
            at        TEXT NOT NULL,
            username  TEXT NOT NULL,
            action    TEXT NOT NULL,
            details   TEXT NOT NULL DEFAULT ''
        )""",
        "CREATE INDEX IF NOT EXISTS idx_audit_at ON audit_log(at)",
        """
        CREATE TABLE IF NOT EXISTS settings (
            key    TEXT PRIMARY KEY,
            value  TEXT NOT NULL
        )""",
        // Current price per night of each room type. Bookings and bills copy the
        // price at the time, so changing a rate never rewrites old bills.
        """
        CREATE TABLE IF NOT EXISTS room_rates (
            room_type     TEXT PRIMARY KEY CHECK (room_type IN ('STANDARD', 'DELUXE', 'FAMILY')),
            nightly_rate  TEXT NOT NULL
        )""",
        """
        CREATE TABLE IF NOT EXISTS rooms (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            number     TEXT    NOT NULL UNIQUE,
            room_type  TEXT    NOT NULL CHECK (room_type IN ('STANDARD', 'DELUXE', 'FAMILY')),
            status     TEXT    NOT NULL DEFAULT 'AVAILABLE'
                       CHECK (status IN ('AVAILABLE', 'OCCUPIED', 'DIRTY', 'MAINTENANCE'))
        )""",
        // check_out is the morning the guest leaves, so nights = check_out - check_in
        """
        CREATE TABLE IF NOT EXISTS reservations (
            id                 INTEGER PRIMARY KEY AUTOINCREMENT,
            guest_name         TEXT    NOT NULL,
            guest_phone        TEXT    NOT NULL DEFAULT '',
            company            TEXT    NOT NULL DEFAULT '',
            company_pays_room  INTEGER NOT NULL DEFAULT 0,
            room_id            INTEGER NOT NULL REFERENCES rooms(id),
            check_in           TEXT    NOT NULL,
            check_out          TEXT    NOT NULL,
            status             TEXT    NOT NULL DEFAULT 'BOOKED'
                               CHECK (status IN ('BOOKED', 'CHECKED_IN', 'CHECKED_OUT', 'CANCELLED')),
            nightly_rate       TEXT    NOT NULL,
            vat_percent        TEXT    NOT NULL,
            notes              TEXT    NOT NULL DEFAULT '',
            created_by         TEXT    NOT NULL,
            created_at         TEXT    NOT NULL,
            CHECK (check_out > check_in)
        )""",
        "CREATE INDEX IF NOT EXISTS idx_res_room_dates ON reservations(room_id, check_in, check_out)",
        // One line on a guest's bill: a charge (bar, laundry...) or a payment.
        // Entries are never deleted; a mistake is voided, so the history stays.
        """
        CREATE TABLE IF NOT EXISTS folio_entries (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            reservation_id  INTEGER NOT NULL REFERENCES reservations(id),
            kind            TEXT    NOT NULL CHECK (kind IN ('CHARGE', 'PAYMENT', 'REFUND')),
            category        TEXT    NOT NULL,
            description     TEXT    NOT NULL DEFAULT '',
            amount          TEXT    NOT NULL,
            payer           TEXT    NOT NULL CHECK (payer IN ('GUEST', 'COMPANY')),
            posted_at       TEXT    NOT NULL,
            posted_by       TEXT    NOT NULL,
            voided          INTEGER NOT NULL DEFAULT 0,
            void_reason     TEXT    NOT NULL DEFAULT ''
        )""",
        "CREATE INDEX IF NOT EXISTS idx_folio_res ON folio_entries(reservation_id)"
    };

    private final Connection connection;

    private Database(String url) {
        try {
            this.connection = DriverManager.getConnection(url);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                for (String ddl : SCHEMA) {
                    st.execute(ddl);
                }
                upgrade(st);
            }
        } catch (SQLException e) {
            throw new DataException("Could not open the database (" + url + ")", e);
        }
    }

    /**
     * Brings a database made by an older version of the app up to date.
     * CREATE TABLE IF NOT EXISTS never changes a table that already exists, so
     * columns added later have to be added here, once, without touching the data.
     */
    private static void upgrade(Statement st) throws SQLException {
        if (!hasColumn(st, "users", "last_login_at")) {
            st.execute("ALTER TABLE users ADD COLUMN last_login_at TEXT");
            LOG.info("Upgraded the users table: added last_login_at");
        }
    }

    private static boolean hasColumn(Statement st, String table, String column) throws SQLException {
        // table is one of our own constants, never user input, so building the SQL here is safe
        try (java.sql.ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Opens (creating if needed) the database file at the given path. */
    public static Database open(Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new DataException("Could not create the folder for " + file, e);
        }
        LOG.info(() -> "Using database " + file.toAbsolutePath());
        return new Database("jdbc:sqlite:" + file.toAbsolutePath());
    }

    /** A throw-away database for tests. */
    public static Database inMemory() {
        return new Database("jdbc:sqlite::memory:");
    }

    /**
     * data/lodgebilling.db in the folder the app is started from (the project folder
     * under gradlew run and IntelliJ). data/ is in .gitignore so it never ends up in Git.
     */
    public static Path defaultPath() {
        return Path.of("data", "lodgebilling.db");
    }

    /**
     * Runs several writes as one unit: either all of them are saved or none are.
     * E.g. check-in must update the booking AND the room; a crash between the two
     * must not leave a checked-in guest in a room still marked Available.
     */
    public <T> T inTransaction(java.util.function.Supplier<T> work) {
        try {
            if (!connection.getAutoCommit()) {
                return work.get();   // already inside a transaction: join it
            }
            connection.setAutoCommit(false);
            try {
                T result = work.get();
                connection.commit();
                return result;
            } catch (RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DataException("The database transaction failed", e);
        }
    }

    Connection connection() {
        return connection;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            LOG.warning("Could not close the database: " + e.getMessage());
        }
    }
}
