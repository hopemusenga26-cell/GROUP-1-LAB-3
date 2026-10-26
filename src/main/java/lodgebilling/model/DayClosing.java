package lodgebilling.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A day whose takings were counted and signed off. Short or over is the counted cash minus the expected. */
public record DayClosing(LocalDate date, String closedBy, LocalDateTime closedAt, BigDecimal expectedCash,
                         BigDecimal countedCash, String note) {

    public BigDecimal difference() {
        return countedCash.subtract(expectedCash);
    }
}
