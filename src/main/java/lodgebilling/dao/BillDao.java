package lodgebilling.dao;

import lodgebilling.model.Bill;
import lodgebilling.model.BillInput;
import lodgebilling.model.PaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Saved receipts. Only the inputs are stored; totals are recalculated by
 * {@link Bill} on load, so the stored data can never disagree with the billing rules.
 */
public interface BillDao {

    /** Stores a new bill. The bill number is never reused, even after a delete. */
    Bill insert(BillInput in, LocalDateTime issuedAt, String createdBy);

    /** Every saved bill, oldest first, with later payments included in what was paid. */
    List<Bill> findAll();

    /** Records a later payment on a bill; it counts towards the bill's paid amount from then on. */
    void addPayment(String receiptNumber, PaymentMethod method, BigDecimal amount, LocalDateTime at, String by);

    void delete(String receiptNumber);
}
