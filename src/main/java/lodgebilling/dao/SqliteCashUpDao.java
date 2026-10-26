package lodgebilling.dao;

import lodgebilling.model.CashUp;
import lodgebilling.model.DayClosing;
import lodgebilling.model.PaymentMethod;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The cash-up, counted in SQL in whole ngwee like {@link SqliteReportDao}. */
public class SqliteCashUpDao implements CashUpDao {

    // A folio payment's category is its method; refunds are change given back in cash.
    // Quick bills record no method for what was paid when they were issued, and the
    // desk takes that in cash, so it is counted as cash.
    private static final String TAKINGS = """
            SELECT who, method, SUM(ngwee) FROM (
                SELECT posted_by AS who, category AS method,
                       CASE kind WHEN 'REFUND' THEN -1 ELSE 1 END
                           * CAST(ROUND(CAST(amount AS REAL) * 100) AS INTEGER) AS ngwee
                FROM folio_entries
                WHERE kind IN ('PAYMENT', 'REFUND') AND voided = 0 AND substr(posted_at, 1, 10) = ?
                UNION ALL
                SELECT created_by, 'CASH', CAST(ROUND(CAST(paid AS REAL) * 100) AS INTEGER)
                FROM bills WHERE substr(issued_at, 1, 10) = ?
                UNION ALL
                SELECT paid_by, method, CAST(ROUND(CAST(amount AS REAL) * 100) AS INTEGER)
                FROM bill_payments WHERE substr(paid_at, 1, 10) = ?
            )
            GROUP BY who, method
            HAVING SUM(ngwee) <> 0
            ORDER BY who, method
            """;

    private final Connection db;

    public SqliteCashUpDao(Database database) {
        this.db = database.connection();
    }

    @Override
    public List<CashUp.Takings> takings(LocalDate date) {
        List<CashUp.Takings> rows = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(TAKINGS)) {
            for (int i = 1; i <= 3; i++) {
                ps.setString(i, date.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new CashUp.Takings(rs.getString(1), PaymentMethod.valueOf(rs.getString(2)),
                            BigDecimal.valueOf(rs.getLong(3), 2)));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new DataException("Could not count the takings for " + date, e);
        }
    }

    @Override
    public Optional<DayClosing> closing(LocalDate date) {
        try (PreparedStatement ps = db.prepareStatement("SELECT closed_by, closed_at, expected_cash, counted_cash, "
                + "note FROM day_closings WHERE business_date = ?")) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new DayClosing(date, rs.getString(1),
                        LocalDateTime.parse(rs.getString(2)), new BigDecimal(rs.getString(3)),
                        new BigDecimal(rs.getString(4)), rs.getString(5))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataException("Could not look up the closing for " + date, e);
        }
    }

    @Override
    public void close(DayClosing c) {
        String sql = "INSERT INTO day_closings (business_date, closed_by, closed_at, expected_cash, counted_cash, "
                + "note) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, c.date().toString());
            ps.setString(2, c.closedBy());
            ps.setString(3, c.closedAt().toString());
            ps.setString(4, c.expectedCash().toPlainString());
            ps.setString(5, c.countedCash().toPlainString());
            ps.setString(6, c.note());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataException("Could not close " + c.date(), e);
        }
    }

    @Override
    public void reopen(LocalDate date) {
        try (PreparedStatement ps = db.prepareStatement("DELETE FROM day_closings WHERE business_date = ?")) {
            ps.setString(1, date.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataException("Could not reopen " + date, e);
        }
    }
}
