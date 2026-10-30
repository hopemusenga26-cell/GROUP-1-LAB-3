package lodgebilling.model;

import java.time.LocalDate;
import java.util.List;

/**
 * The end-of-day checklist: everything the front desk should sort out before the day is
 * signed off, so tomorrow starts with a true picture of who is in the lodge.
 *
 * @param overdueArrivals bookings that should have arrived before today and never did (no-shows)
 * @param arrivingToday   bookings due today that have not checked in yet (may still come tonight)
 * @param dueOut          guests due to leave today who are still checked in
 * @param overstays       guests who should have left before today and are still checked in
 * @param roomsNotReady   rooms a guest arrives in today or tomorrow that are dirty or under maintenance
 * @param dayClosed       whether the cash-up for the day has been done
 */
public record NightAudit(LocalDate date, List<Reservation> overdueArrivals, List<Reservation> arrivingToday,
                         List<Reservation> dueOut, List<Reservation> overstays, List<Room> roomsNotReady,
                         boolean dayClosed) {

    /** Things to fix before closing the day (expected arrivals today are only a reminder). */
    public int openItems() {
        return overdueArrivals.size() + dueOut.size() + overstays.size() + roomsNotReady.size();
    }

    public boolean allClear() {
        return openItems() == 0;
    }
}
