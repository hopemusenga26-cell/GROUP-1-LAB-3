package lodgebilling.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The folio maths, kept free of JavaFX and SQL so it is easy to test.
 *
 *   room charge = nights x nightly rate, minus the long-stay discount (same rule as quick bills)
 *   subtotal    = room charge + extras            (per payer)
 *   VAT         = subtotal x VAT %                (per payer, rounded to the ngwee)
 *   balance     = subtotal + VAT - payments       (per payer)
 *
 * VAT is rounded per payer and the overall VAT is the sum of those, so the
 * split bills always add up exactly to the overall bill.
 */
public final class FolioCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private FolioCalculator() { }

    /** What a stay will cost before any extras: room nights less the discount, plus VAT. */
    public static BigDecimal estimate(BigDecimal nightlyRate, int nights, BigDecimal vatPercent) {
        BigDecimal charge = Bill.roomCharge(nightlyRate, nights);
        BigDecimal net = charge.subtract(Bill.discount(charge, nights));
        return Money.scale(net.add(Money.scale(net.multiply(vatPercent).divide(HUNDRED))));
    }

    public static FolioSummary summarise(Reservation stay, List<FolioEntry> entries) {
        // Room nights, with the same discount rule as the quick bill
        BigDecimal roomCharge = Bill.roomCharge(stay.nightlyRate(), stay.nights());
        BigDecimal discount = Bill.discount(roomCharge, stay.nights());
        Payer roomPayer = stay.companyPaysRoom() && stay.hasCompany() ? Payer.COMPANY : Payer.GUEST;

        Map<Payer, BigDecimal> charged = new EnumMap<>(Payer.class);
        Map<Payer, BigDecimal> paid = new EnumMap<>(Payer.class);
        for (Payer p : Payer.values()) {
            charged.put(p, BigDecimal.ZERO);
            paid.put(p, BigDecimal.ZERO);
        }
        BigDecimal roomNet = roomCharge.subtract(discount);
        charged.merge(roomPayer, roomNet, BigDecimal::add);

        BigDecimal extras = BigDecimal.ZERO;
        for (FolioEntry e : entries) {
            if (e.voided()) {
                continue;
            }
            if (e.isCharge()) {
                charged.merge(e.payer(), e.amount(), BigDecimal::add);
                extras = extras.add(e.amount());
            } else if (e.isPayment()) {
                paid.merge(e.payer(), e.amount(), BigDecimal::add);
            } else {
                paid.merge(e.payer(), e.amount().negate(), BigDecimal::add);   // refund
            }
        }

        List<FolioSummary.PayerLine> lines = new ArrayList<>();
        BigDecimal vatTotal = BigDecimal.ZERO;
        BigDecimal paidTotal = BigDecimal.ZERO;
        for (Payer p : Payer.values()) {
            if (p == Payer.COMPANY && !stay.hasCompany()) {
                continue;
            }
            BigDecimal sub = Money.scale(charged.get(p));
            BigDecimal vat = Money.scale(sub.multiply(stay.vatPercent()).divide(HUNDRED));
            BigDecimal total = sub.add(vat);
            BigDecimal pd = Money.scale(paid.get(p));
            lines.add(new FolioSummary.PayerLine(p, stay.payerName(p), sub, vat, total, pd, total.subtract(pd)));
            vatTotal = vatTotal.add(vat);
            paidTotal = paidTotal.add(pd);
        }

        BigDecimal subtotal = Money.scale(roomNet.add(extras));
        BigDecimal total = subtotal.add(vatTotal);
        return new FolioSummary(roomCharge, discount, Money.scale(extras),
                subtotal, stay.vatPercent(), vatTotal, total, paidTotal, total.subtract(paidTotal), lines);
    }
}
