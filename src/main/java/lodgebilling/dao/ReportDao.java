package lodgebilling.dao;

import lodgebilling.model.DailyStat;

import java.time.LocalDate;
import java.util.List;

/** Figures for the Reports page, counted by the database. */
public interface ReportDao {

    /** One row per day from from to to (both included), including days with nothing booked. */
    List<DailyStat> daily(LocalDate from, LocalDate to);
}
