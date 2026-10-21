package lodgebilling.dao;

import lodgebilling.model.RateCard;
import lodgebilling.model.RoomType;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** Room rates and simple key/value settings such as the VAT %. */
public class SqliteSettingsDao implements SettingsDao {

    private final Connection db;

    public SqliteSettingsDao(Database database) {
        this.db = database.connection();
    }

    @Override
    public Optional<String> get(String key) {
        try (PreparedStatement ps = db.prepareStatement("SELECT value FROM settings WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataException("Could not read setting " + key, e);
        }
    }

    @Override
    public void put(String key, String value) {
        // UPSERT: insert, or update if the key is already there
        String sql = "INSERT INTO settings (key, value) VALUES (?, ?) "
                + "ON CONFLICT(key) DO UPDATE SET value = excluded.value";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataException("Could not save setting " + key, e);
        }
    }

    @Override
    public RateCard loadRateCard(BigDecimal defaultVat) {
        Map<RoomType, BigDecimal> rates = new EnumMap<>(RoomType.class);
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT room_type, nightly_rate FROM room_rates")) {
            while (rs.next()) {
                rates.put(RoomType.valueOf(rs.getString(1)), new BigDecimal(rs.getString(2)));
            }
        } catch (SQLException e) {
            throw new DataException("Could not read room rates", e);
        }
        BigDecimal vat = get(VAT_PERCENT).map(BigDecimal::new).orElse(defaultVat);
        return new RateCard(rates, vat);
    }

    @Override
    public void saveRate(RoomType type, BigDecimal rate) {
        String sql = "INSERT INTO room_rates (room_type, nightly_rate) VALUES (?, ?) "
                + "ON CONFLICT(room_type) DO UPDATE SET nightly_rate = excluded.nightly_rate";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, type.name());
            ps.setString(2, rate.toPlainString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataException("Could not save the rate for " + type, e);
        }
    }
}
