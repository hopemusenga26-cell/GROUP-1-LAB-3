package lodgebilling.model;

import java.math.BigDecimal;

/** One payment to record: who pays, how, how much, and an optional reference (e.g. a mobile money ref). */
public record PaymentLine(Payer payer, PaymentMethod method, BigDecimal amount, String reference) { }
