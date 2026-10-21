package lodgebilling.dao;

import lodgebilling.model.AuditAction;
import lodgebilling.model.RoomType;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One-off migration: the earlier version of the app kept receipts in a tab-separated
 * file (~/.lodgebilling/receipts.tsv). On the first start of this version, every
 * receipt in that file is copied into the database, keeping its receipt number,
 * and the file is renamed to receipts.tsv.imported so it is never read twice.
 *
 * File layout, one receipt per line:
 * receipt no, issued at, guest name, room type, nights, extras, paid, notes
 * (tabs and line breaks inside names/notes were escaped as \t and \n).
 */
public final class LegacyReceiptImporter {

    private static final Logger LOG = Logger.getLogger(LegacyReceiptImporter.class.getName());
    static final String IMPORTED_BY = "(imported)";

    /** What happened, for the log and the tests. */
    public record Result(int imported, int skippedDamaged, int renumbered) { }

    private LegacyReceiptImporter() { }

    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), ".lodgebilling", "receipts.tsv");
    }

    /**
     * Imports the file if it is there; does nothing otherwise. Never stops the app
     * from starting: on any problem the file is left in place and the error is logged.
     */
    public static Result importOnce(Database database, AuditLogDao audit, Path file) {
        if (!Files.isRegularFile(file)) {
            return new Result(0, 0, 0);
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            // All or nothing: a crash half-way must not leave half the receipts copied
            Result result = database.inTransaction(() -> copy(database.connection(), lines));
            Files.move(file, file.resolveSibling(file.getFileName() + ".imported"),
                    StandardCopyOption.REPLACE_EXISTING);
            audit.record("system", AuditAction.RECEIPTS_IMPORTED, result.imported() + " receipts from " + file.getFileName()
                    + (result.skippedDamaged() > 0 ? ", " + result.skippedDamaged() + " damaged lines skipped" : "")
                    + (result.renumbered() > 0 ? ", " + result.renumbered() + " given new numbers" : ""));
            LOG.info(() -> "Imported old receipts: " + result);
            return result;
        } catch (IOException | DataException e) {
            LOG.log(Level.WARNING, "Could not import " + file + "; it was left as it is", e);
            return new Result(0, 0, 0);
        }
    }

    private static Result copy(Connection db, List<String> lines) {
        int imported = 0;
        int damaged = 0;
        int renumbered = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split("\t", -1);
            OldReceipt r;
            try {
                r = new OldReceipt(Long.parseLong(f[0].substring(1)), LocalDateTime.parse(f[1]), unescape(f[2]),
                        RoomType.valueOf(f[3]), Integer.parseInt(f[4]), new BigDecimal(f[5]),
                        new BigDecimal(f[6]), unescape(f[7]));
            } catch (RuntimeException e) {
                damaged++;
                continue;
            }
            Long number = numberFor(db, r);
            if (number == null || number != r.number()) {
                renumbered++;
            }
            insert(db, number, r);
            imported++;
        }
        return new Result(imported, damaged, renumbered);
    }

    /** One receipt as the old file stored it. The old app only had the fixed prices. */
    record OldReceipt(long number, LocalDateTime issuedAt, String guest, RoomType room, int nights,
                      BigDecimal extras, BigDecimal paid, String notes) { }

    /**
     * Which receipt number the imported receipt gets.
     * Returns null to let the database hand out the next free number.
     */
    static Long numberFor(Connection db, OldReceipt r) {
        // Keep the old number when it is free: it is printed on the guest's copy
        if (!numberTaken(db, r.number())) {
            return r.number();
        }
        return null;
    }

    static boolean numberTaken(Connection db, long number) {
        try (PreparedStatement ps = db.prepareStatement("SELECT 1 FROM bills WHERE id = ?")) {
            ps.setLong(1, number);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new DataException("Could not check receipt number " + number, e);
        }
    }

    private static void insert(Connection db, Long number, OldReceipt r) {
        // Giving SQLite an explicit id also moves AUTOINCREMENT past it,
        // so new receipts carry on after the highest imported number
        String sql = "INSERT INTO bills (id, guest_name, room_type, nightly_rate, nights, extras, paid, notes, "
                + "issued_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            if (number == null) {
                ps.setNull(1, java.sql.Types.INTEGER);
            } else {
                ps.setLong(1, number);
            }
            ps.setString(2, r.guest());
            ps.setString(3, r.room().name());
            ps.setString(4, r.room().getNightlyRate().toPlainString());
            ps.setInt(5, r.nights());
            ps.setString(6, r.extras().toPlainString());
            ps.setString(7, r.paid().toPlainString());
            ps.setString(8, number == null || number != r.number()
                    ? (r.notes() + " [was R" + String.format("%03d", r.number()) + "]").trim()
                    : r.notes());
            ps.setString(9, r.issuedAt().toString());
            ps.setString(10, IMPORTED_BY);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataException("Could not import receipt R" + r.number(), e);
        }
    }

    /** Reverses the old file's escaping of backslashes, tabs and line breaks. */
    static String unescape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                out.append(switch (next) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> next;
                });
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
