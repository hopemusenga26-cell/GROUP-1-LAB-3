package lodgebilling.dao;

import lodgebilling.model.Bill;
import lodgebilling.model.BillInput;
import lodgebilling.model.RoomType;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL for the bills table. Only the inputs are stored; totals are recalculated by
 * {@link Bill} on load, so the stored data can never disagree with the billing rules.
 */
public class SqliteBillDao implements BillDao {

    private final Connection db;

    public SqliteBillDao(Database database) {
        this.db = database.connection();
    }

    /** Stores a new bill. The bill number comes from the AUTOINCREMENT id, so it is never reused. */
    @Override
    public Bill insert(BillInput in, LocalDateTime issuedAt, String createdBy) {
        String sql = "INSERT INTO bills (guest_name, room_type, nightly_rate, nights, extras, paid, notes, "
                + "issued_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = db.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, in.name());
            ps.setString(2, in.room().name());
            ps.setString(3, in.nightlyRate().toPlainString());
            ps.setInt(4, in.nights());
            ps.setString(5, in.extras().toPlainString());
            ps.setString(6, in.paid().toPlainString());
            ps.setString(7, in.notes());
            ps.setString(8, issuedAt.toString());
            ps.setString(9, createdBy);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Bill(Bill.idFor(keys.getLong(1)), in.name(), in.room(), in.nightlyRate(), in.nights(),
                        in.extras(), in.paid(), in.notes(), issuedAt, createdBy);
            }
        } catch (SQLException e) {
            throw new DataException("Could not save the bill for " + in.name(), e);
        }
    }

    @Override
    public List<Bill> findAll() {
        String sql = "SELECT id, guest_name, room_type, nightly_rate, nights, extras, paid, notes, issued_at, created_by "
                + "FROM bills ORDER BY id";
        List<Bill> bills = new ArrayList<>();
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                bills.add(new Bill(Bill.idFor(rs.getLong("id")), rs.getString("guest_name"),
                        RoomType.valueOf(rs.getString("room_type")), new BigDecimal(rs.getString("nightly_rate")),
                        rs.getInt("nights"),
                        new BigDecimal(rs.getString("extras")), new BigDecimal(rs.getString("paid")),
                        rs.getString("notes"), LocalDateTime.parse(rs.getString("issued_at")),
                        rs.getString("created_by")));
            }
            return bills;
        } catch (SQLException e) {
            throw new DataException("Could not load the saved bills", e);
        }
    }

    @Override
    public void delete(String receiptNumber) {
        try (PreparedStatement ps = db.prepareStatement("DELETE FROM bills WHERE id = ?")) {
            ps.setLong(1, Bill.numberOf(receiptNumber));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataException("Could not delete bill " + receiptNumber, e);
        }
    }
}
