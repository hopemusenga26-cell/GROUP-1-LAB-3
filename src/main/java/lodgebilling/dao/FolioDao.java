package lodgebilling.dao;

import lodgebilling.model.FolioEntry;
import lodgebilling.model.Payer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Folio lines (charges and payments) on a stay. Lines are voided, never deleted. */
public interface FolioDao {

    FolioEntry insert(long reservationId, FolioEntry.Kind kind, String category, String description,
                      BigDecimal amount, Payer payer, LocalDateTime at, String by);

    /** One stay's lines, in the order they were posted. */
    List<FolioEntry> findForReservation(long reservationId);

    /** The lines of the given stays, grouped by reservation id, in one query. */
    Map<Long, List<FolioEntry>> findForReservations(Collection<Long> reservationIds);

    /** Marks a line as voided, with the reason. A line already voided is left as it is. */
    void voidEntry(long entryId, String reason);
}
