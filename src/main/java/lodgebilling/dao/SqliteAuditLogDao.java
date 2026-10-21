package lodgebilling.dao;

import lodgebilling.model.AuditAction;
import lodgebilling.model.AuditEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** The audit log, kept in SQLite. */
public class SqliteAuditLogDao implements AuditLogDao {

    private static final Logger LOG = Logger.getLogger(SqliteAuditLogDao.class.getName());

    private final Connection db;
    private final Clock clock;

    public SqliteAuditLogDao(Database database, Clock clock) {
        this.db = database.connection();
        this.clock = clock;
    }

    /** Writes one entry. A failure here is logged but never stops the user's work. */
    @Override
    public void record(String username, AuditAction action, String details) {
        String sql = "INSERT INTO audit_log (at, username, action, details) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, Instant.now(clock).toString());
            ps.setString(2, username == null ? "" : username);
            ps.setString(3, action.name());
            ps.setString(4, details == null ? "" : details);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("Could not write audit entry " + action + ": " + e.getMessage());
        }
    }

    /** The newest entries first. */
    @Override
    public List<AuditEntry> latest(int limit) {
        String sql = "SELECT at, username, action, details FROM audit_log ORDER BY id DESC LIMIT ?";
        List<AuditEntry> entries = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new AuditEntry(Instant.parse(rs.getString("at")), rs.getString("username"),
                            AuditAction.valueOf(rs.getString("action")), rs.getString("details")));
                }
            }
            return entries;
        } catch (SQLException e) {
            throw new DataException("Could not read the audit log", e);
        }
    }
}
