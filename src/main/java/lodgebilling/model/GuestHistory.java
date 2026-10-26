package lodgebilling.model;

import java.math.BigDecimal;
import java.util.List;

/** A returning guest's earlier stays (newest first), found by phone number, and what they still owe from them. */
public record GuestHistory(List<Reservation> stays, BigDecimal owed) {

    public static final GuestHistory NONE = new GuestHistory(List.of(), BigDecimal.ZERO);

    public boolean isEmpty() {
        return stays.isEmpty();
    }

    public Reservation last() {
        return stays.get(0);
    }
}
