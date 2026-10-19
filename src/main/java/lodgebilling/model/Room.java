package lodgebilling.model;

/** One physical room, e.g. "201", a Deluxe room that is currently Dirty. */
public record Room(long id, String number, RoomType type, RoomStatus status) {
    public String getNumber()     { return number; }
    public RoomType getType()     { return type; }
    public RoomStatus getStatus() { return status; }

    /** "201 (Deluxe)", plus the status when the room is not ready, e.g. "201 (Deluxe) - Dirty". */
    @Override
    public String toString() {
        String name = number + " (" + type + ")";
        return status == null || status == RoomStatus.AVAILABLE ? name : name + " - " + status;
    }
}
