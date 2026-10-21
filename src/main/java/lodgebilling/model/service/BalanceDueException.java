package lodgebilling.model.service;

import lodgebilling.model.Money;

import java.math.BigDecimal;

/** Check-out refused because money is still owed. The screen may offer a forced check-out to admins. */
public class BalanceDueException extends LodgeException {

    private final BigDecimal balance;

    public BalanceDueException(String guest, BigDecimal balance) {
        super(guest + " still owes " + Money.format(balance) + ". Record the payment before checking out.");
        this.balance = balance;
    }

    public BigDecimal balance() {
        return balance;
    }
}
