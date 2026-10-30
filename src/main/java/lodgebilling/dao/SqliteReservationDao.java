package lodgebilling.dao;

import lodgebilling.model.Reservation;
import lodgebilling.model.ReservationStatus;
import lodgebilling.model.Room;
import lodgebilling.model.RoomStatus;
import lodgebilling.model.RoomType;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** SQL for reservations. Every read joins the room, so callers get the room number and type too. */
public class SqliteReservationDao implements ReservationDao {

    private static final String SELECT = """
            SELECT r.id, r.guest_name, r.guest_phone, r.company, r.company_pays_room,
                   r.check_in, r.check_out, r.status, r.nightly_rate, r.vat_percent, r.notes, r.created_by, r.created_at,
                   m.id AS room_id, m.number AS room_number, m.room_type, m.status AS room_status
            FROM reservations r JOIN rooms m ON m.id = r.room_id
            """;

    private final Connection db;

    public SqliteReservationDao(Database database) {
        this.db = database.connection();
    }

    /** All stays, newest arrival first. */
    @Override
    public List<Reservation> findAll() {
        return query(SELECT + " ORDER BY r.check_in DESC, r.id DESC");
    }

    @Override
    public Optional<Reservation> findById(long id) {
        List<Reservation> found = query(SELECT + " WHERE r.id = ?", id);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** Stays that happened or will happen on a night between from (inclusive) and to (exclusive). */
    @Override
    public List<Reservation> findOverlapping(LocalDate from, LocalDate to) {
        return query(SELECT + " WHERE r.status NOT IN ('CANCELLED', 'NO_SHOW') AND r.check_in < ? AND r.check_out > ?"
                + " ORDER BY m.number, r.check_in", to.toString(), from.toString());
    }

    /**
     * True if another live booking already has this room on any of these nights.
     * Two stays overlap when each one starts before the other ends.
     */
    @Override
    public boolean roomTaken(long roomId, LocalDate checkIn, LocalDate checkOut, long ignoreReservationId) {
        String sql = "SELECT 1 FROM reservations WHERE room_id = ? AND id <> ? "
                + "AND status IN ('BOOKED', 'CHECKED_IN') AND check_in < ? AND check_out > ? LIMIT 1";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setLong(1, roomId);
            ps.setLong(2, ignoreReservationId);
            ps.setString(3, checkOut.toString());
            ps.setString(4, checkIn.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new DataException("Could not check room availability", e);
        }
    }

    @Override
    public Set<Long> takenRoomIds(LocalDate checkIn, LocalDate checkOut, long ignoreReservationId) {
        String sql = "SELECT DISTINCT room_id FROM reservations WHERE id <> ? "
                + "AND status IN ('BOOKED', 'CHECKED_IN') AND check_in < ? AND check_out > ?";
        Set<Long> taken = new HashSet<>();
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setLong(1, ignoreReservationId);
            ps.setString(2, checkOut.toString());
            ps.setString(3, checkIn.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    taken.add(rs.getLong(1));
                }
            }
            return taken;
        } catch (SQLException e) {
            throw new DataException("Could not check room availability", e);
        }
    }

    @Override
    public long insert(String guestName, String phone, String company, boolean companyPaysRoom, long roomId,
                       LocalDate checkIn, LocalDate checkOut, BigDecimal rate, BigDecimal vat, String notes,
                       String createdBy, LocalDateTime now) {
        String sql = "INSERT INTO reservations (guest_name, guest_phone, company, company_pays_room, room_id, "
                + "check_in, check_out, nightly_rate, vat_percent, notes, created_by, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = db.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, guestName);
            ps.setString(2, phone);
            ps.setString(3, company);
            ps.setInt(4, companyPaysRoom ? 1 : 0);
            ps.setLong(5, roomId);
            ps.setString(6, checkIn.toString());
            ps.setString(7, checkOut.toString());
            ps.setString(8, rate.toPlainString());
            ps.setString(9, vat.toPlainString());
            ps.setString(10, notes);
            ps.setString(11, createdBy);
            ps.setString(12, now.toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new DataException("Could not save the booking for " + guestName, e);
        }
    }

    @Override
    public void updateDetails(long id, String guestName, String phone, String company, boolean companyPaysRoom,
                              long roomId, LocalDate checkIn, LocalDate checkOut, String notes) {
        String sql = "UPDATE reservations SET guest_name = ?, guest_phone = ?, company = ?, company_pays_room = ?, "
                + "room_id = ?, check_in = ?, check_out = ?, notes = ? WHERE id = ?";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, guestName);
            ps.setString(2, phone);
            ps.setString(3, company);
            ps.setInt(4, companyPaysRoom ? 1 : 0);
            ps.setLong(5, roomId);
            ps.setString(6, checkIn.toString());
            ps.setString(7, checkOut.toString());
            ps.setString(8, notes);
            ps.setLong(9, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataException("Could not update booking #" + id, e);
        }
    }

    @Override
    public void setStatus(long id, ReservationStatus status) {
        try (PreparedStatement ps = db.prepareStatement("UPDATE reservations SET status = ? WHERE id = ?")) {
            ps.setString(1, status.name());
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataException("Could not update booking #" + id, e);
        }
    }

    private List<Reservation> query(String sql, Object... params) {
        List<Reservation> list = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(map(rs));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new DataException("Could not read bookings", e);
        }
    }

    private static Reservation map(ResultSet rs) throws SQLException {
        Room room = new Room(rs.getLong("room_id"), rs.getString("room_number"),
                RoomType.valueOf(rs.getString("room_type")), RoomStatus.valueOf(rs.getString("room_status")));
        return new Reservation(rs.getLong("id"), rs.getString("guest_name"), rs.getString("guest_phone"),
                rs.getString("company"), rs.getInt("company_pays_room") == 1, room,
                LocalDate.parse(rs.getString("check_in")), LocalDate.parse(rs.getString("check_out")),
                ReservationStatus.valueOf(rs.getString("status")), new BigDecimal(rs.getString("nightly_rate")),
                new BigDecimal(rs.getString("vat_percent")), rs.getString("notes"), rs.getString("created_by"),
                LocalDateTime.parse(rs.getString("created_at")));
    }
}
