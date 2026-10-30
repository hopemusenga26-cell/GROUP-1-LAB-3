package lodgebilling.model;

import java.time.LocalDate;

/**
 * One room for housekeeping to clean, and how soon a guest needs it. No guest names:
 * housekeeping only needs to know which room and when.
 *
 * @param nextArrival   the next day a booked guest arrives in this room, or null if none is booked
 * @param afterCheckOut true if a guest is still in the room and leaves today: clean it once they are out
 */
public record CleaningTask(Room room, LocalDate nextArrival, boolean afterCheckOut) {

    /** "Guest arriving today", "Guest arriving tomorrow", "Next guest Fri 30 Oct" or "No guest booked". */
    public String urgency(LocalDate today) {
        if (nextArrival == null) {
            return "No guest booked";
        }
        if (!nextArrival.isAfter(today)) {
            return "Guest arriving today";
        }
        if (nextArrival.equals(today.plusDays(1))) {
            return "Guest arriving tomorrow";
        }
        return "Next guest " + nextArrival.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"));
    }

    /** Needed today: someone is due in it before tomorrow. */
    public boolean urgent(LocalDate today) {
        return nextArrival != null && !nextArrival.isAfter(today);
    }
}
