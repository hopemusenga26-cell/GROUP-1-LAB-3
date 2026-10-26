package lodgebilling.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What was taken on one day, per member of staff and payment method, for the end-of-day count.
 * Refunds (change given back) are already taken off, so each amount is what should be in hand.
 *
 * @param closing how the day was signed off, or null while it is still open
 */
public record CashUp(LocalDate date, List<Takings> takings, DayClosing closing) {

    /** What one member of staff took by one payment method. */
    public record Takings(String staff, PaymentMethod method, BigDecimal amount) { }

    public boolean isClosed() {
        return closing != null;
    }

    /** Totals per payment method, every method listed (zero if nothing was taken that way). */
    public Map<PaymentMethod, BigDecimal> byMethod() {
        Map<PaymentMethod, BigDecimal> totals = new EnumMap<>(PaymentMethod.class);
        for (PaymentMethod m : PaymentMethod.values()) totals.put(m, BigDecimal.ZERO);
        for (Takings t : takings) totals.merge(t.method(), t.amount(), BigDecimal::add);
        return totals;
    }

    /** The cash that should be in the drawer. */
    public BigDecimal expectedCash() {
        return byMethod().get(PaymentMethod.CASH);
    }

    public BigDecimal total() {
        return takings.stream().map(Takings::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
