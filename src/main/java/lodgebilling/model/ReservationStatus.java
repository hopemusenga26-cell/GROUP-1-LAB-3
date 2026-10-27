package lodgebilling.model;

/**
 * Where a stay is in its life: booked -> checked in -> checked out. A booking can also end
 * as cancelled (called off before arrival) or no-show (the guest never came).
 */
public enum ReservationStatus {
    BOOKED("Booked"),
    CHECKED_IN("In house"),
    CHECKED_OUT("Checked out"),
    CANCELLED("Cancelled"),
    NO_SHOW("No-show");

    private final String label;

    ReservationStatus(String label) {
        this.label = label;
    }

    /** Booked and in-house stays hold the room; checked-out and cancelled ones no longer do. */
    public boolean holdsRoom() {
        return this == BOOKED || this == CHECKED_IN;
    }

    public String styleClass() {
        return "stay-" + name().toLowerCase().replace('_', '-');
    }

    @Override
    public String toString() {
        return label;
    }
}
