package lodgebilling.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/** Money helpers: every amount is a BigDecimal with 2 decimal places. */
public final class Money {

    private Money() { }

    public static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Reads an amount typed by a person, e.g. "150", "150.50" or "1,500".
     * Empty if it is not a plain amount with at most 2 decimal places.
     */
    public static Optional<BigDecimal> parse(String text) {
        String t = text == null ? "" : text.trim().replace(",", "");
        return t.matches("\\d{1,7}(\\.\\d{1,2})?") ? Optional.of(new BigDecimal(t)) : Optional.empty();
    }

    /** Formats an amount the way the screen shows it, e.g. "K 2812.50". */
    public static String format(BigDecimal value) {
        return "K " + scale(value).toPlainString();
    }
}
