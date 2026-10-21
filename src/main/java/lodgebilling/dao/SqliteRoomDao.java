package lodgebilling.dao;

import lodgebilling.model.Room;
import lodgebilling.model.RoomStatus;
import lodgebilling.model.RoomType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQL for the rooms table. */
public class SqliteRoomDao implements RoomDao {

    private final Connection db;

    public SqliteRoomDao(Database database) {
        this.db = database.connection();
    }

    @Override
    public List<Room> findAll() {
        List<Room> rooms = new ArrayList<>();
        // Sort "101" < "201" < "1001" numerically when the numbers are numeric
        String sql = "SELECT id, number, room_type, status FROM rooms ORDER BY LENGTH(number), number";
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rooms.add(map(rs));
            }
            return rooms;
        } catch (SQLException e) {
            throw new DataException("Could not list rooms", e);
        }
    }

    @Override
    public Optional<Room> findById(long id) {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id, number, room_type, status FROM rooms WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataException("Could not look up room #" + id, e);
        }
    }

    @Override
    public boolean numberExists(String number) {
        try (PreparedStatement ps = db.prepareStatement("SELECT 1 FROM rooms WHERE number = ?")) {
            ps.setString(1, number);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new DataException("Could not check room " + number, e);
        }
    }

    @Override
    public Room insert(String number, RoomType type) {
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO rooms (number, room_type) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, number);
            ps.setString(2, type.name());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new Room(keys.getLong(1), number, type, RoomStatus.AVAILABLE);
            }
        } catch (SQLException e) {
            throw new DataException("Could not add room " + number, e);
        }
    }

    @Override
    public void setStatus(long roomId, RoomStatus status) {
        try (PreparedStatement ps = db.prepareStatement("UPDATE rooms SET status = ? WHERE id = ?")) {
            ps.setString(1, status.name());
            ps.setLong(2, roomId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataException("Could not update room #" + roomId, e);
        }
    }

    @Override
    public int count() {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM rooms")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new DataException("Could not count rooms", e);
        }
    }

    private static Room map(ResultSet rs) throws SQLException {
        return new Room(rs.getLong("id"), rs.getString("number"),
                RoomType.valueOf(rs.getString("room_type")), RoomStatus.valueOf(rs.getString("status")));
    }
}
