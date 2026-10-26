package lodgebilling.dao;

import lodgebilling.model.CashUp;
import lodgebilling.model.DayClosing;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** The end-of-day count: what each member of staff took, and which days are closed. */
public interface CashUpDao {

    /** Money taken on that day (guest folios and quick bills), net of refunds, by staff and method. */
    List<CashUp.Takings> takings(LocalDate date);

    Optional<DayClosing> closing(LocalDate date);

    void close(DayClosing closing);

    void reopen(LocalDate date);
}
