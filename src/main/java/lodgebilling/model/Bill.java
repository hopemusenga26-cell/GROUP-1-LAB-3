package lodgebilling.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One receipt (one row in the table). Immutable: everything is worked out
 * once in the constructor. The public getters are what PropertyValueFactory reads.
 * A receipt is either a saved quick bill (R001) or a booking or walk-in (BK0001),
 * whose figures come from its folio.
 */
public class Bill {

    // ---- Billing rules ----
    public static final int DISCOUNT_MIN_NIGHTS = 5;
    public static final BigDecimal DISCOUNT_RATE = new BigDecimal("0.05"); // 5% off the room charge

    private final String receiptNumber;
    private final long stayId;   // the booking this receipt shows, or 0 for a quick bill
    private final String guestName;
    private final RoomType roomType;
    private final BigDecimal nightlyRate;
    private final int nights;
    private final BigDecimal extras;
    private final BigDecimal paid;
    private final String notes;
    private final LocalDateTime issuedAt;
    private final String createdBy;

    private final BigDecimal roomCharge;
    private final BigDecimal discount;
    private final BigDecimal vatPercent;
    private final BigDecimal vat;
    private final BigDecimal total;
    private final BigDecimal balance;

    /** An unsaved bill (a preview), issued now by nobody in particular. */
    public Bill(String receiptNumber, String guestName, RoomType roomType, int nights,
                BigDecimal extras, BigDecimal paid, String notes) {
        this(receiptNumber, guestName, roomType, roomType.getNightlyRate(), nights, extras, paid, notes,
                LocalDateTime.now(), "");
    }

    /**
     * @param nightlyRate the price per night when the bill was made (copied, so a
     *                    later price change does not alter an old bill)
     */
    public Bill(String receiptNumber, String guestName, RoomType roomType, BigDecimal nightlyRate, int nights,
                BigDecimal extras, BigDecimal paid, String notes,
                LocalDateTime issuedAt, String createdBy) {
        this.nightlyRate = Money.scale(nightlyRate);
        this.issuedAt = issuedAt;
        this.createdBy = createdBy;
        this.receiptNumber = receiptNumber;
        this.guestName = guestName;
        this.roomType = roomType;
        this.nights = nights;
        this.extras = Money.scale(extras);
        this.paid = Money.scale(paid);
        this.notes = notes;
        this.stayId = 0;
        this.roomCharge = roomCharge(this.nightlyRate, nights);
        this.discount = discount(roomCharge, nights);

        // No tax
        this.vatPercent = BigDecimal.ZERO;
        this.vat = Money.scale(BigDecimal.ZERO);
        this.total = roomCharge.subtract(discount).add(this.extras);
        this.balance = total.subtract(this.paid);
    }

    /** A booking or walk-in as a receipt: the folio has already worked out the charges, VAT and payments. */
    private Bill(Reservation stay, FolioSummary s) {
        this.receiptNumber = stay.reference();
        this.stayId = stay.id();
        this.guestName = stay.guestName();
        this.roomType = stay.room().type();
        this.nightlyRate = Money.scale(stay.nightlyRate());
        this.nights = stay.nights();
        this.extras = s.extras();
        this.paid = s.paid();
        this.notes = stay.notes();
        this.issuedAt = stay.createdAt();
        this.createdBy = stay.createdBy();
        this.roomCharge = s.roomCharge();
        this.discount = s.discount();
        this.vatPercent = s.vatPercent();
        this.vat = s.vat();
        this.total = s.total();
        this.balance = s.balance();
    }

    public static Bill forStay(Reservation stay, FolioSummary summary) {
        return new Bill(stay, summary);
    }

    // ---- The room-charge rule, shared by quick bills, folios and booking estimates ----

    /** Price per night x nights. */
    public static BigDecimal roomCharge(BigDecimal nightlyRate, int nights) {
        return Money.scale(nightlyRate.multiply(BigDecimal.valueOf(nights)));
    }

    /** 5 nights or more: 5% off the room charge (extras get no discount). */
    public static BigDecimal discount(BigDecimal roomCharge, int nights) {
        return nights >= DISCOUNT_MIN_NIGHTS
                ? Money.scale(roomCharge.multiply(DISCOUNT_RATE))
                : Money.scale(BigDecimal.ZERO);
    }

    /** Builds a bill from validated form values. Use an empty id for an unsaved preview. */
    public static Bill of(String receiptNumber, BillInput in) {
        return new Bill(receiptNumber, in.name(), in.room(), in.nightlyRate(), in.nights(), in.extras(), in.paid(),
                in.notes(), LocalDateTime.now(), "");
    }

    /** Receipt number 7 is shown as "R007". */
    public static String idFor(long number) {
        return String.format("R%03d", number);
    }

    /** True for a booking or walk-in, which lives in its folio rather than in the bills table. */
    public boolean isStay() {
        return stayId != 0;
    }

    /** The booking behind a stay receipt (0 for a quick bill). */
    public long stayId() {
        return stayId;
    }

    /** The same quick bill after a later payment, e.g. the rest of a 50% deposit. */
    public Bill withPayment(BigDecimal amount) {
        return new Bill(receiptNumber, guestName, roomType, nightlyRate, nights, extras, paid.add(amount), notes,
                issuedAt, createdBy);
    }

    /** True for the same receipt, even after a payment changed its figures. */
    public boolean sameReceipt(Bill other) {
        return other != null && receiptNumber.equals(other.receiptNumber);
    }

    /** "R007" back to 7. */
    public static long numberOf(String receiptNumber) {
        return Long.parseLong(receiptNumber.substring(1));
    }

    /** "5%", built from DISCOUNT_RATE so the screen can never disagree with the maths. */
    public static String discountPercentLabel() {
        return DISCOUNT_RATE.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }

    public String getReceiptNumber()         { return receiptNumber; }
    public String getGuestName()      { return guestName; }
    public RoomType getRoomType()     { return roomType; }
    public BigDecimal getNightlyRate() { return nightlyRate; }
    public int getNights()            { return nights; }
    public BigDecimal getExtras()     { return extras; }
    public BigDecimal getPaid()       { return paid; }
    public String getNotes()          { return notes; }
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public String getCreatedBy()      { return createdBy; }
    public BigDecimal getRoomCharge() { return roomCharge; }
    public BigDecimal getDiscount()   { return discount; }
    public BigDecimal getVatPercent() { return vatPercent; }
    public BigDecimal getVat()        { return vat; }
    public BigDecimal getTotal()      { return total; }
    public BigDecimal getBalance()    { return balance; }
}
