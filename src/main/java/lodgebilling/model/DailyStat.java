package lodgebilling.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** One day of the daily report. Revenue figures are before discount and VAT. */
public record DailyStat(LocalDate date, int rooms, int roomsSold, BigDecimal roomRevenue,
                        BigDecimal paymentsReceived) {

    public double occupancyPercent() {
        return rooms == 0 ? 0 : 100.0 * roomsSold / rooms;
    }

    /** Average daily rate: room revenue per room actually sold. */
    public BigDecimal adr() {
        return roomsSold == 0 ? BigDecimal.ZERO
                : roomRevenue.divide(BigDecimal.valueOf(roomsSold), 2, RoundingMode.HALF_UP);
    }

    /** Revenue per available room: room revenue spread over every room, sold or not. */
    public BigDecimal revPar() {
        return rooms == 0 ? BigDecimal.ZERO
                : roomRevenue.divide(BigDecimal.valueOf(rooms), 2, RoundingMode.HALF_UP);
    }
}
