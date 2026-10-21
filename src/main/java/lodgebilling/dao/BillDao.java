package lodgebilling.dao;

import lodgebilling.model.Bill;
import lodgebilling.model.BillInput;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Saved receipts. Only the inputs are stored; totals are recalculated by
 * {@link Bill} on load, so the stored data can never disagree with the billing rules.
 */
public interface BillDao {

    /** Stores a new bill. The bill number is never reused, even after a delete. */
    Bill insert(BillInput in, LocalDateTime issuedAt, String createdBy);

    /** Every saved bill, oldest first. */
    List<Bill> findAll();

    void delete(String receiptNumber);
}
