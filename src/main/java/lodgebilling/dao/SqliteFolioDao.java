package lodgebilling.dao;

import lodgebilling.model.FolioEntry;
import lodgebilling.model.Payer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** SQL for folio lines (charges and payments). Lines are voided, never deleted. */
public class SqliteFolioDao implements FolioDao {

    private static final String COLUMNS = "id, reservation_id, kind, category, description, amount, payer, "
            + "posted_at, posted_by, voided, void_reason";

    private final Connection db;

    public SqliteFolioDao(Database database) {
        this.db = database.connection();
    }

    @Override
    public FolioEntry insert(long reservationId, FolioEntry.Kind kind, String category, String description,
                             BigDecimal amount, Payer payer, LocalDateTime at, String by) {
        String sql = "INSERT INTO folio_entries (reservation_id, kind, category, description, amount, payer, "
                + "posted_at, posted_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = db.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, reservationId);
            ps.setString(2, kind.name());
            ps.setString(3, category);
            ps.setString(4, description);
            ps.setString(5, amount.toPlainString());
            ps.setString(6, payer.name());
            ps.setString(7, at.toString());
            ps.setString(8, by);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new FolioEntry(keys.getLong(1), reservationId, kind, category, description, amount,
                        payer, at, by, false, "");
            }
        } catch (SQLException e) {
            throw new DataException("Could not post to folio #" + reservationId, e);
        }
    }

    @Override
    public List<FolioEntry> findForReservation(long reservationId) {
        List<FolioEntry> list = new ArrayList<>();
        String sql = "SELECT " + COLUMNS + " FROM folio_entries WHERE reservation_id = ? ORDER BY id";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setLong(1, reservationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(map(rs));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new DataException("Could not read folio #" + reservationId, e);
        }
    }

    /** Every line of every folio, grouped by reservation: one query instead of one per stay (no N+1). */
    @Override
    public Map<Long, List<FolioEntry>> findAllGrouped() {
        Map<Long, List<FolioEntry>> map = new HashMap<>();
        String sql = "SELECT " + COLUMNS + " FROM folio_entries ORDER BY id";
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                FolioEntry e = map(rs);
                map.computeIfAbsent(e.reservationId(), k -> new ArrayList<>()).add(e);
            }
            return map;
        } catch (SQLException e) {
            throw new DataException("Could not read folios", e);
        }
    }

    @Override
    public void voidEntry(long entryId, String reason) {
        try (PreparedStatement ps = db.prepareStatement(
                "UPDATE folio_entries SET voided = 1, void_reason = ? WHERE id = ? AND voided = 0")) {
            ps.setString(1, reason);
            ps.setLong(2, entryId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataException("Could not void line #" + entryId, e);
        }
    }

    private static FolioEntry map(ResultSet rs) throws SQLException {
        return new FolioEntry(rs.getLong("id"), rs.getLong("reservation_id"),
                FolioEntry.Kind.valueOf(rs.getString("kind")), rs.getString("category"),
                rs.getString("description"), new BigDecimal(rs.getString("amount")),
                Payer.valueOf(rs.getString("payer")), LocalDateTime.parse(rs.getString("posted_at")),
                rs.getString("posted_by"), rs.getInt("voided") == 1, rs.getString("void_reason"));
    }
}
