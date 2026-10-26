package lodgebilling.dao;

import lodgebilling.model.Reservation;
import lodgebilling.model.ReservationStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Bookings and stays. Every reservation read comes with its room. */
public interface ReservationDao {

    /** All stays, newest arrival first. */
    List<Reservation> findAll();

    Optional<Reservation> findById(long id);

    /** Stays that hold a room on at least one night between from (inclusive) and to (exclusive). */
    List<Reservation> findOverlapping(LocalDate from, LocalDate to);

    /**
     * True if another live booking already has this room on any of these nights.
     * Two stays overlap when each one starts before the other ends.
     */
    boolean roomTaken(long roomId, LocalDate checkIn, LocalDate checkOut, long ignoreReservationId);

    /** The rooms a live booking holds on any night of this range (one query for every room). */
    Set<Long> takenRoomIds(LocalDate checkIn, LocalDate checkOut, long ignoreReservationId);

    /** Saves a new booking and returns its id. */
    long insert(String guestName, String phone, String company, boolean companyPaysRoom, long roomId,
                LocalDate checkIn, LocalDate checkOut, BigDecimal rate, BigDecimal vat, String notes,
                String createdBy, LocalDateTime now);

    void updateDetails(long id, String guestName, String phone, String company, boolean companyPaysRoom,
                       long roomId, LocalDate checkIn, LocalDate checkOut, String notes);

    void setStatus(long id, ReservationStatus status);
}
