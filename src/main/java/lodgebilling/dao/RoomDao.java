package lodgebilling.dao;

import lodgebilling.model.Room;
import lodgebilling.model.RoomStatus;
import lodgebilling.model.RoomType;

import java.util.List;
import java.util.Optional;

/** The lodge's rooms and their housekeeping status. */
public interface RoomDao {

    /** Every room, in room-number order ("101" before "201" before "1001"). */
    List<Room> findAll();

    Optional<Room> findById(long id);

    boolean numberExists(String number);

    /** Adds a room; new rooms start out Available. */
    Room insert(String number, RoomType type);

    void setStatus(long roomId, RoomStatus status);

    int count();
}
