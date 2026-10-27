package lodgebilling.model.service;

import lodgebilling.dao.AuditLogDao;
import lodgebilling.dao.CashUpDao;
import lodgebilling.model.AuditAction;
import lodgebilling.model.Bill;
import lodgebilling.model.CashUp;
import lodgebilling.dao.Database;
import lodgebilling.dao.FolioDao;
import lodgebilling.dao.ReportDao;
import lodgebilling.model.DailyStat;
import lodgebilling.dao.ReservationDao;
import lodgebilling.dao.RoomDao;
import lodgebilling.dao.SettingsDao;
import lodgebilling.model.ChargeCategory;
import lodgebilling.model.DashboardData;
import lodgebilling.model.DayClosing;
import lodgebilling.model.FolioCalculator;
import lodgebilling.model.FolioEntry;
import lodgebilling.model.FolioSummary;
import lodgebilling.model.GuestHistory;
import lodgebilling.model.Money;
import lodgebilling.model.NightAudit;
import lodgebilling.model.Payer;
import lodgebilling.model.PaymentLine;
import lodgebilling.model.PaymentMethod;
import lodgebilling.model.RateCard;
import lodgebilling.model.PersonName;
import lodgebilling.model.Reservation;
import lodgebilling.model.ReservationForm;
import lodgebilling.model.ReservationStatus;
import lodgebilling.model.Room;
import lodgebilling.model.RoomStatus;
import lodgebilling.model.RoomType;
import lodgebilling.model.security.Permission;
import lodgebilling.model.security.Session;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The front-desk rules: rooms, bookings, check-in/out and guest folios.
 * Every method that changes something checks the user's permission first and
 * writes an audit entry, so the screens cannot skip either.
 */
public class LodgeService {

    /** Zambia's standard VAT rate, used until an admin sets another. */
    public static final BigDecimal DEFAULT_VAT = new BigDecimal("16");
    public static final int MAX_NIGHTS = 60;
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000");
    public static final int SEARCH_LIMIT = 30;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");

    private static final String PHONE_PATTERN = "[+0-9 ()\\-]{0,20}";

    /** A record of what a stay costs, plus its lines, for the folio window. */
    public record Folio(Reservation stay, List<FolioEntry> entries, FolioSummary summary) { }

    private final Database database;
    private final RoomDao rooms;
    private final ReservationDao reservations;
    private final FolioDao folios;
    private final SettingsDao settings;
    private final ReportDao reports;
    private final CashUpDao cashUps;
    private final AuditLogDao audit;
    private final Clock clock;
    private final RateCard rateCard;

    /**
     * The service works only through the DAO interfaces, so it does not know how the data is stored.
     * The database is needed only to group several writes into one transaction.
     */
    public LodgeService(Database database, RoomDao rooms, ReservationDao reservations, FolioDao folios,
                        SettingsDao settings, ReportDao reports, CashUpDao cashUps, AuditLogDao audit,
                        Clock clock) {
        this.database = database;
        this.rooms = rooms;
        this.reservations = reservations;
        this.folios = folios;
        this.settings = settings;
        this.reports = reports;
        this.cashUps = cashUps;
        this.audit = audit;
        this.clock = clock;
        seedIfEmpty();
        this.rateCard = settings.loadRateCard(DEFAULT_VAT);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** First run: a small lodge with nine rooms and the original prices. */
    private void seedIfEmpty() {
        if (rooms.count() == 0) {
            database.inTransaction(() -> {
                for (String n : List.of("101", "102", "103", "104")) rooms.insert(n, RoomType.STANDARD);
                for (String n : List.of("201", "202", "203")) rooms.insert(n, RoomType.DELUXE);
                for (String n : List.of("301", "302")) rooms.insert(n, RoomType.FAMILY);
                for (RoomType t : RoomType.values()) settings.saveRate(t, t.getNightlyRate());
                settings.put(SettingsDao.VAT_PERCENT, DEFAULT_VAT.toPlainString());
                return null;
            });
        }
    }

    // =====================================================================
    // Rates and rooms
    // =====================================================================

    /** The live rate card; the same object is updated in place when an admin changes prices. */
    public RateCard rateCard() {
        return rateCard;
    }

    public void saveRates(Session session, Map<RoomType, BigDecimal> newRates, BigDecimal vatPercent)
            throws LodgeException {
        session.require(Permission.MANAGE_ROOMS);
        for (RoomType t : RoomType.values()) {
            BigDecimal r = newRates.get(t);
            if (r == null || r.signum() <= 0 || r.compareTo(MAX_AMOUNT) > 0) {
                throw new LodgeException("Enter a price above zero for " + t + " rooms.");
            }
        }
        if (vatPercent == null || vatPercent.signum() < 0 || vatPercent.compareTo(new BigDecimal("50")) > 0) {
            throw new LodgeException("VAT must be between 0 and 50 %.");
        }
        database.inTransaction(() -> {
            newRates.forEach(settings::saveRate);
            settings.put(SettingsDao.VAT_PERCENT, vatPercent.stripTrailingZeros().toPlainString());
            return null;
        });
        rateCard.replaceWith(new RateCard(newRates, vatPercent));
        StringBuilder detail = new StringBuilder();
        newRates.forEach((t, r) -> detail.append(t).append(' ').append(Money.format(r)).append(", "));
        audit.record(session.username(), AuditAction.RATES_CHANGED, detail + "VAT " + vatPercent + "%");
    }

    public List<Room> rooms() {
        return rooms.findAll();
    }

    public Room addRoom(Session session, String number, RoomType type) throws LodgeException {
        session.require(Permission.MANAGE_ROOMS);
        String n = roomNumber(number, type);
        if (rooms.numberExists(n)) {
            throw new LodgeException("Room " + n + " already exists.");
        }
        Room room = rooms.insert(n, type);
        audit.record(session.username(), AuditAction.ROOM_ADDED, n + " (" + type + ")");
        return room;
    }

    /** Renumbers a room or changes its type. Bookings keep the price they were made at. */
    public void updateRoom(Session session, Room room, String number, RoomType type) throws LodgeException {
        session.require(Permission.MANAGE_ROOMS);
        Room current = rooms.findById(room.id()).orElseThrow(() -> new LodgeException("That room no longer exists."));
        String n = roomNumber(number, type);
        if (!n.equals(current.number()) && rooms.numberExists(n)) {
            throw new LodgeException("Room " + n + " already exists.");
        }
        rooms.update(current.id(), n, type);
        audit.record(session.username(), AuditAction.ROOM_UPDATED,
                current.number() + " (" + current.type() + ") -> " + n + " (" + type + ")");
    }

    /**
     * Removes a room that was never booked, e.g. one added by mistake. A room with bookings
     * is part of the lodge's history (folios and receipts name it), so it can only be put
     * under maintenance, which keeps it out of new bookings.
     */
    public void deleteRoom(Session session, Room room) throws LodgeException {
        session.require(Permission.MANAGE_ROOMS);
        Room current = rooms.findById(room.id()).orElseThrow(() -> new LodgeException("That room no longer exists."));
        if (rooms.hasBookings(current.id())) {
            throw new LodgeException("Room " + current.number() + " has bookings on record, so it cannot be deleted. "
                    + "Mark it Maintenance to keep it out of new bookings.");
        }
        rooms.delete(current.id());
        audit.record(session.username(), AuditAction.ROOM_DELETED, current.number() + " (" + current.type() + ")");
    }

    /** A typed room number, checked; the type must be chosen too. */
    private static String roomNumber(String number, RoomType type) throws LodgeException {
        String n = number == null ? "" : number.trim();
        if (!n.matches("[A-Za-z0-9\\-]{1,10}")) {
            throw new LodgeException("Room numbers are 1 to 10 letters, digits or hyphens (e.g. 104 or A-2).");
        }
        if (type == null) {
            throw new LodgeException("Choose a room type.");
        }
        return n;
    }

    /** Housekeeping: Available / Dirty / Maintenance. Occupied is set only by check-in. */
    public void setRoomStatus(Session session, Room room, RoomStatus status) throws LodgeException {
        session.require(Permission.UPDATE_ROOM_STATUS);
        Room current = rooms.findById(room.id()).orElseThrow(() -> new LodgeException("That room no longer exists."));
        if (status == RoomStatus.OCCUPIED) {
            throw new LodgeException("A room becomes Occupied when a guest checks in.");
        }
        if (current.status() == RoomStatus.OCCUPIED) {
            throw new LodgeException("Room " + current.number() + " has a guest in it. Check the guest out first.");
        }
        rooms.setStatus(current.id(), status);
        audit.record(session.username(), AuditAction.ROOM_STATUS, current.number() + ": " + current.status() + " -> " + status);
    }

    // =====================================================================
    // Bookings
    // =====================================================================

    public List<Reservation> reservations() {
        return reservations.findAll();
    }

    public List<Reservation> reservationsBetween(LocalDate from, LocalDate to) {
        return reservations.findOverlapping(from, to);
    }

    public Reservation reservation(long id) throws LodgeException {
        return reservations.findById(id).orElseThrow(() -> new LodgeException("That booking no longer exists."));
    }

    /**
     * Rooms free for every night of the stay (ignoring the booking being edited, if any).
     * Rooms under maintenance are left out, except the room the edited booking already has.
     */
    public List<Room> freeRooms(LocalDate checkIn, LocalDate checkOut, long ignoreReservationId) {
        List<Room> free = new ArrayList<>();
        if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) {
            return free;
        }
        long keepRoomId = ignoreReservationId == 0 ? -1
                : reservations.findById(ignoreReservationId).map(res -> res.room().id()).orElse(-1L);
        Set<Long> taken = reservations.takenRoomIds(checkIn, checkOut, ignoreReservationId);
        for (Room r : rooms.findAll()) {
            boolean usable = r.status() != RoomStatus.MAINTENANCE || r.id() == keepRoomId;
            if (usable && !taken.contains(r.id())) {
                free.add(r);
            }
        }
        return free;
    }

    public Reservation book(Session session, ReservationForm form) throws LodgeException {
        session.require(Permission.MANAGE_RESERVATIONS);
        ReservationForm f = clean(form);
        checkForm(f, 0);
        if (f.checkIn().isBefore(today())) {
            throw new LodgeException("The check-in date is in the past.");
        }
        checkNotUnderMaintenance(f.room());
        long id = reservations.insert(f.guestName(), f.phone(), f.company(), f.companyPaysRoom(), f.room().id(),
                f.checkIn(), f.checkOut(), rateCard.rateFor(f.room().type()), rateCard.vatPercent(), f.notes(),
                session.username(), now());
        Reservation r = reservation(id);
        audit.record(session.username(), AuditAction.BOOKING_CREATED, describe(r));
        return r;
    }

    /** A guest arriving without a booking: book from today and check in straight away. */
    public Reservation walkIn(Session session, ReservationForm form) throws LodgeException {
        return walkIn(session, form, null);
    }

    /**
     * A walk-in who pays now (in full or a deposit). The booking, the check-in and the
     * payment are saved together: if any of them is refused, none of them is kept.
     *
     * @param payment what the guest pays now, or null if nothing yet
     */
    public Reservation walkIn(Session session, ReservationForm form, PaymentLine payment) throws LodgeException {
        session.require(Permission.MANAGE_RESERVATIONS);
        if (payment != null) {
            session.require(Permission.POST_FOLIO);
        }
        ReservationForm f = clean(form);
        if (!today().equals(f.checkIn())) {
            throw new LodgeException("A walk-in starts today.");
        }
        checkRoomReady(f.room());
        return atomically(() -> {
            Reservation in = checkIn(session, book(session, f).id());
            if (payment != null) {
                recordPayment(session, in.id(), payment.method(), payment.amount(), payment.payer(),
                        payment.reference());
            }
            return in;
        });
    }

    /**
     * Before arrival everything can change. Once the guest is in, only their
     * details and the departure date can (the room and arrival are history).
     */
    public Reservation update(Session session, long id, ReservationForm form) throws LodgeException {
        session.require(Permission.MANAGE_RESERVATIONS);
        Reservation old = reservation(id);
        ReservationForm f = clean(form);
        if (old.status() == ReservationStatus.CHECKED_IN) {
            if (f.room().id() != old.room().id() || !f.checkIn().equals(old.checkIn())) {
                throw new LodgeException("The guest is already in. Only their details and departure date can change.");
            }
            if (f.checkOut().isBefore(today())) {
                throw new LodgeException("The departure date cannot be in the past.");
            }
        } else if (old.status() != ReservationStatus.BOOKED) {
            throw new LodgeException("A " + old.status().toString().toLowerCase() + " stay cannot be changed.");
        }
        checkForm(f, id);
        if (f.room().id() != old.room().id()) {
            checkNotUnderMaintenance(f.room());   // moving to another room: it must be usable
        }
        if (f.companyPaysRoom() || hasCompanyLines(id)) {
            if (!f.hasCompany()) {
                throw new LodgeException("This folio has lines billed to a company. Keep the company name.");
            }
        }
        reservations.updateDetails(id, f.guestName(), f.phone(), f.company(), f.companyPaysRoom(), f.room().id(),
                f.checkIn(), f.checkOut(), f.notes());
        Reservation r = reservation(id);
        audit.record(session.username(), AuditAction.BOOKING_UPDATED, describe(r));
        return r;
    }

    public void cancel(Session session, long id) throws LodgeException {
        session.require(Permission.MANAGE_RESERVATIONS);
        Reservation r = reservation(id);
        if (r.status() != ReservationStatus.BOOKED) {
            throw new LodgeException("Only a booking that has not arrived yet can be cancelled.");
        }
        Folio folio = folio(id);
        if (folio.summary().paid().signum() > 0) {
            throw new LodgeException("This booking has a deposit of " + Money.format(folio.summary().paid())
                    + ". An admin must void (refund) it before the booking is cancelled.");
        }
        reservations.setStatus(id, ReservationStatus.CANCELLED);
        audit.record(session.username(), AuditAction.BOOKING_CANCELLED, describe(r));
    }

    public Reservation checkIn(Session session, long id) throws LodgeException {
        session.require(Permission.MANAGE_RESERVATIONS);
        Reservation r = reservation(id);
        if (r.status() != ReservationStatus.BOOKED) {
            throw new LodgeException("Only a booked stay can be checked in (this one is " + r.status() + ").");
        }
        if (r.checkIn().isAfter(today())) {
            throw new LodgeException("This booking starts on " + r.checkIn() + ". Change the dates to check in early.");
        }
        if (!r.checkOut().isAfter(today())) {
            throw new LodgeException("This booking ended on " + r.checkOut() + ". Change the dates first.");
        }
        Room room = rooms.findById(r.room().id()).orElseThrow();
        checkRoomReady(room);
        database.inTransaction(() -> {
            reservations.setStatus(id, ReservationStatus.CHECKED_IN);
            rooms.setStatus(room.id(), RoomStatus.OCCUPIED);
            return null;
        });
        audit.record(session.username(), AuditAction.CHECK_IN, describe(r));
        return reservation(id);
    }

    /**
     * Checks the guest out. Leaving early shortens the stay to the nights used;
     * if that means they paid too much, the change is recorded as a cash refund.
     * With money still owed it is refused, unless force is true and the user
     * may force a check-out (admin), in which case the debt stays on the folio.
     */
    public Folio checkOut(Session session, long id, boolean force) throws LodgeException {
        session.require(Permission.MANAGE_RESERVATIONS);
        Reservation r = reservation(id);
        if (r.status() != ReservationStatus.CHECKED_IN) {
            throw new LodgeException("Only a guest who is in house can be checked out.");
        }
        LocalDate newCheckOut = leavingDate(r);
        FolioSummary summary = FolioCalculator.summarise(withCheckOut(r, newCheckOut), folios.findForReservation(id));

        if (summary.balance().signum() > 0) {
            if (!force) {
                throw new BalanceDueException(r.guestName(), summary.balance());
            }
            session.require(Permission.FORCE_CHECKOUT);
        }
        if (summary.balance().signum() < 0) {
            checkDayOpen(today());   // the change given back comes out of today's takings
        }
        database.inTransaction(() -> {
            if (!newCheckOut.equals(r.checkOut())) {
                reservations.updateDetails(id, r.guestName(), r.guestPhone(), r.company(), r.companyPaysRoom(),
                        r.room().id(), r.checkIn(), newCheckOut, r.notes());
            }
            reservations.setStatus(id, ReservationStatus.CHECKED_OUT);
            rooms.setStatus(r.room().id(), RoomStatus.DIRTY);
            for (FolioSummary.PayerLine line : summary.payers()) {
                if (line.balance().signum() < 0) {
                    folios.insert(id, FolioEntry.Kind.REFUND, PaymentMethod.CASH.name(),
                            "Change for nights not used", line.balance().negate(), line.payer(), now(),
                            session.username());
                }
            }
            return null;
        });
        if (summary.balance().signum() > 0) {
            audit.record(session.username(), AuditAction.FORCED_CHECKOUT,
                    describe(r) + ", left owing " + Money.format(summary.balance()));
        } else {
            audit.record(session.username(), AuditAction.CHECK_OUT, describe(r));
        }
        return folio(id);
    }

    /** What the guest owes if they check out now (leaving early bills only the nights used). */
    public FolioSummary checkOutQuote(long id) throws LodgeException {
        Reservation r = reservation(id);
        return FolioCalculator.summarise(withCheckOut(r, leavingDate(r)), folios.findForReservation(id));
    }

    /**
     * Takes what is still owed and checks the guest out, as one step: if a payment
     * is refused, nothing is saved and the guest stays checked in.
     */
    public Folio settleAndCheckOut(Session session, long id, List<PaymentLine> payments) throws LodgeException {
        session.require(Permission.POST_FOLIO);
        boolean paying = payments.stream().anyMatch(p -> p.amount() != null && p.amount().signum() > 0);
        return atomically(() -> {
            if (paying) {   // nothing to take (e.g. leaving early, already paid): just check out
                recordPayments(session, id, payments);
            }
            return checkOut(session, id, false);
        });
    }

    /** Leaving before the booked date: bill only the nights used (at least one). */
    private LocalDate leavingDate(Reservation r) {
        LocalDate leaving = today().isAfter(r.checkIn()) ? today() : r.checkIn().plusDays(1);
        return leaving.isBefore(r.checkOut()) ? leaving : r.checkOut();
    }

    // =====================================================================
    // Folio: charges and payments
    // =====================================================================

    public Folio folio(long reservationId) throws LodgeException {
        Reservation r = reservation(reservationId);
        List<FolioEntry> entries = folios.findForReservation(reservationId);
        return new Folio(r, entries, FolioCalculator.summarise(r, entries));
    }

    /** The worked-out bill of each given stay, using one query for all their lines. */
    public Map<Long, FolioSummary> summaries(List<Reservation> stays) {
        Map<Long, FolioSummary> out = new HashMap<>();
        for (Folio f : folios(stays)) {
            out.put(f.stay().id(), f.summary());
        }
        return out;
    }

    /** The folios of the given stays, reading all their lines in one query. */
    private List<Folio> folios(List<Reservation> stays) {
        Map<Long, List<FolioEntry>> lines = folios.findForReservations(stays.stream().map(Reservation::id).toList());
        return stays.stream().map(r -> {
            List<FolioEntry> entries = lines.getOrDefault(r.id(), List.of());
            return new Folio(r, entries, FolioCalculator.summarise(r, entries));
        }).toList();
    }

    /** Every booking and walk-in (except cancelled ones and no-shows) as a receipt, with its folio's figures. */
    public List<Bill> stayReceipts() {
        List<Reservation> stays = reservations.findAll().stream()
                .filter(r -> r.status() != ReservationStatus.CANCELLED && r.status() != ReservationStatus.NO_SHOW)
                .toList();
        Map<Long, FolioSummary> sums = summaries(stays);
        return stays.stream().map(r -> Bill.forStay(r, sums.get(r.id()))).toList();
    }

    /** One stay as a receipt, e.g. to refresh its row after a payment or to print it. */
    public Bill stayReceipt(long reservationId) throws LodgeException {
        Folio f = folio(reservationId);
        return Bill.forStay(f.stay(), f.summary());
    }

    public FolioEntry postCharge(Session session, long reservationId, ChargeCategory category, String description,
                                 BigDecimal amount, Payer payer) throws LodgeException {
        session.require(Permission.POST_FOLIO);
        Reservation r = reservation(reservationId);
        if (!r.status().holdsRoom()) {
            throw new LodgeException("Charges can only be added to a booked or in-house stay.");
        }
        Objects.requireNonNull(category, "category");
        checkAmount(amount);
        checkPayer(r, payer);
        FolioEntry e = folios.insert(reservationId, FolioEntry.Kind.CHARGE, category.name(), clean(description),
                Money.scale(amount), payer, now(), session.username());
        audit.record(session.username(), AuditAction.CHARGE_POSTED,
                r.reference() + " " + category + " " + Money.format(amount) + " to " + r.payerName(payer));
        return e;
    }

    /** One payment. Refused if it is more than that payer owes (stops typing 10000 for 1000). */
    public FolioEntry recordPayment(Session session, long reservationId, PaymentMethod method, BigDecimal amount,
                                    Payer payer, String reference) throws LodgeException {
        session.require(Permission.POST_FOLIO);
        Folio folio = folio(reservationId);
        Reservation r = folio.stay();
        if (r.status() == ReservationStatus.CANCELLED || r.status() == ReservationStatus.NO_SHOW) {
            throw new LodgeException("This booking was " + r.status().toString().toLowerCase()
                    + (r.status() == ReservationStatus.NO_SHOW ? " (the guest never came)." : "."));
        }
        Objects.requireNonNull(method, "method");
        checkAmount(amount);
        checkPayer(r, payer);
        BigDecimal owed = owedBy(folio.summary(), payer);
        if (amount.compareTo(owed) > 0) {
            throw new LodgeException(r.payerName(payer) + " owes only " + Money.format(owed) + ".");
        }
        checkDayOpen(today());
        FolioEntry e = folios.insert(reservationId, FolioEntry.Kind.PAYMENT, method.name(), clean(reference),
                Money.scale(amount), payer, now(), session.username());
        audit.record(session.username(), AuditAction.PAYMENT_RECORDED,
                r.reference() + " " + method + " " + Money.format(amount) + " from " + r.payerName(payer));
        return e;
    }

    /**
     * Several payments at once, e.g. the guest and the company each paying their share.
     * All or nothing: if one is refused, none is saved. Lines with no amount are skipped.
     */
    public void recordPayments(Session session, long reservationId, List<PaymentLine> payments)
            throws LodgeException {
        session.require(Permission.POST_FOLIO);
        List<PaymentLine> real = payments.stream()
                .filter(p -> p.amount() != null && p.amount().signum() > 0).toList();
        if (real.isEmpty()) {
            throw new LodgeException("Enter at least one amount.");
        }
        atomically(() -> {
            for (PaymentLine p : real) {
                recordPayment(session, reservationId, p.method(), p.amount(), p.payer(), p.reference());
            }
            return null;
        });
    }

    /** Split payment: the guest and the company each pay their share at once, all or nothing. */
    public void recordSplitPayment(Session session, long reservationId, PaymentMethod guestMethod,
                                   BigDecimal guestAmount, PaymentMethod companyMethod, BigDecimal companyAmount,
                                   String reference) throws LodgeException {
        if (!folio(reservationId).stay().hasCompany()) {
            throw new LodgeException("Add a company to the booking to split the bill.");
        }
        recordPayments(session, reservationId, List.of(
                new PaymentLine(Payer.GUEST, guestMethod, guestAmount, reference),
                new PaymentLine(Payer.COMPANY, companyMethod, companyAmount, reference)));
    }

    /** Work that may be refused; see {@link #atomically}. */
    @FunctionalInterface
    private interface Work<T> {
        T run() throws LodgeException;
    }

    /**
     * Runs several writes as one transaction. A refusal anywhere rolls all of them back,
     * and the original message reaches the caller. Nested calls join the outer transaction.
     */
    private <T> T atomically(Work<T> work) throws LodgeException {
        try {
            return database.inTransaction(() -> {
                try {
                    return work.run();
                } catch (LodgeException e) {
                    throw new Rollback(e);
                }
            });
        } catch (Rollback r) {
            throw r.cause;
        }
    }

    /** Carries a checked exception out of a transaction so it can be rolled back. */
    private static final class Rollback extends RuntimeException {
        final LodgeException cause;
        Rollback(LodgeException cause) {
            super(cause.getMessage(), null, false, false);
            this.cause = cause;
        }
    }

    /** Cancels a line posted by mistake. The line stays visible, struck through, with the reason. */
    public void voidEntry(Session session, FolioEntry entry, String reason) throws LodgeException {
        session.require(Permission.VOID_FOLIO_ENTRY);
        String why = clean(reason);
        if (why.length() < 3) {
            throw new LodgeException("Give a reason for voiding (e.g. \"posted to wrong room\").");
        }
        if (entry.voided()) {
            throw new LodgeException("That line is already void.");
        }
        if (!entry.isCharge()) {
            checkDayOpen(entry.postedAt().toLocalDate());
        }
        folios.voidEntry(entry.id(), why);
        audit.record(session.username(), AuditAction.ENTRY_VOIDED, Reservation.referenceFor(entry.reservationId())
                + " " + entry.kind() + " " + Money.format(entry.amount()) + ": " + why);
    }

    // =====================================================================
    // Dashboard and reports
    // =====================================================================

    public DashboardData dashboard(Session session) {
        session.require(Permission.VIEW_DASHBOARD);
        LocalDate today = today();
        List<Reservation> stays = reservations.findAll();
        List<Reservation> arrivals = new ArrayList<>();
        List<Reservation> departures = new ArrayList<>();
        int inHouse = 0;
        for (Reservation r : stays) {
            if (r.status() == ReservationStatus.BOOKED && !r.checkIn().isAfter(today)) {
                arrivals.add(r);
            }
            if (r.status() == ReservationStatus.CHECKED_IN) {
                inHouse++;
                if (!r.checkOut().isAfter(today)) {
                    departures.add(r);
                }
            }
        }
        BigDecimal outstanding = owing(stays).stream().map(f -> f.summary().balance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<RoomStatus, Integer> byStatus = new EnumMap<>(RoomStatus.class);
        List<Room> all = rooms.findAll();
        for (RoomStatus s : RoomStatus.values()) byStatus.put(s, 0);
        for (Room room : all) byStatus.merge(room.status(), 1, Integer::sum);
        BigDecimal paidToday = reports.daily(today, today).get(0).paymentsReceived();
        return new DashboardData(arrivals, departures, inHouse, all.size(), byStatus, outstanding, paidToday);
    }

    public List<DailyStat> dailyReport(Session session, LocalDate from, LocalDate to) throws LodgeException {
        session.require(Permission.VIEW_REPORTS);
        if (from == null || to == null || to.isBefore(from)) {
            throw new LodgeException("Choose a start date on or before the end date.");
        }
        if (from.plusDays(366).isBefore(to)) {
            throw new LodgeException("Choose a range of at most one year.");
        }
        return reports.daily(from, to);
    }

    /** Stays that still owe money (in house or already gone), largest debt first. */
    public List<Folio> outstanding(Session session) {
        session.require(Permission.VIEW_REPORTS);
        List<Folio> owing = new ArrayList<>(owing(reservations.findAll()));
        owing.sort((a, b) -> b.summary().balance().compareTo(a.summary().balance()));
        return owing;
    }

    /**
     * Stays that match every word typed, each word against the guest's name, phone, company,
     * booking number (BK-0042, or just 42) or room number. Guests in house come first, then
     * bookings still to arrive, then the rest; newest arrival first within each.
     */
    public List<Reservation> search(Session session, String text) {
        session.require(Permission.MANAGE_RESERVATIONS);
        List<String> words = text == null ? List.of()
                : List.of(text.trim().toLowerCase().split("\\s+")).stream().filter(w -> !w.isEmpty()).toList();
        if (words.isEmpty()) {
            return List.of();
        }
        return reservations.findAll().stream()   // already newest arrival first
                .filter(r -> words.stream().allMatch(w -> matches(r, w)))
                .sorted(java.util.Comparator.comparingInt(r -> searchOrder(r.status())))
                .limit(SEARCH_LIMIT)
                .toList();
    }

    private static boolean matches(Reservation r, String word) {
        if (r.guestName().toLowerCase().contains(word) || r.company().toLowerCase().contains(word)
                || r.reference().toLowerCase().contains(word) || r.room().number().equalsIgnoreCase(word)) {
            return true;
        }
        // Part of a phone number, however it was written (+260 97..., 097...)
        String digits = word.replaceAll("\\D", "");
        return word.matches("[+\\d()-]{3,}") && r.guestPhone().replaceAll("\\D", "").contains(digits);
    }

    private static int searchOrder(ReservationStatus status) {
        return switch (status) {
            case CHECKED_IN -> 0;
            case BOOKED -> 1;
            case CHECKED_OUT -> 2;
            case NO_SHOW -> 3;
            case CANCELLED -> 4;
        };
    }

    /**
     * The earlier stays of the guest with this phone number, so a returning guest's details
     * can be filled in and any money they still owe is seen before they are booked again.
     */
    public GuestHistory guestHistory(Session session, String phone) {
        session.require(Permission.MANAGE_RESERVATIONS);
        String key = phoneKey(phone);
        if (key == null) {
            return GuestHistory.NONE;
        }
        List<Reservation> stays = reservations.findAll().stream()
                .filter(r -> r.status() != ReservationStatus.CANCELLED && key.equals(phoneKey(r.guestPhone())))
                .toList();
        BigDecimal owed = owing(stays).stream().map(f -> f.summary().balance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new GuestHistory(stays, owed);
    }

    /**
     * The last 9 digits of a phone number, the same whether it was typed as +260 97 123 4567
     * or 0971234567. Null if there are too few digits to tell guests apart.
     */
    static String phoneKey(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        return digits.length() < 9 ? null : digits.substring(digits.length() - 9);
    }

    /** The stays among these that have been billed (in house or gone) and still owe money. */
    private List<Folio> owing(List<Reservation> stays) {
        List<Reservation> billed = stays.stream().filter(r -> r.status() == ReservationStatus.CHECKED_IN
                || r.status() == ReservationStatus.CHECKED_OUT).toList();
        return folios(billed).stream().filter(f -> f.summary().balance().signum() > 0).toList();
    }

    // =====================================================================
    // End of day
    // =====================================================================

    /**
     * The night audit: no-shows, guests still to arrive or leave, guests who stayed past their
     * check-out, and rooms not ready for the next arrivals. Read-only; nothing is changed.
     */
    public NightAudit nightAudit(Session session) {
        session.require(Permission.CASH_UP);
        LocalDate today = today();
        List<Reservation> overdue = new ArrayList<>();
        List<Reservation> arriving = new ArrayList<>();
        List<Reservation> dueOut = new ArrayList<>();
        List<Reservation> overstays = new ArrayList<>();
        Map<Long, Room> notReady = new java.util.LinkedHashMap<>();
        for (Reservation r : reservations.findAll()) {
            if (r.status() == ReservationStatus.BOOKED) {
                if (r.checkIn().isBefore(today)) {
                    overdue.add(r);
                } else if (r.checkIn().equals(today)) {
                    arriving.add(r);
                }
                // A guest arriving today or tomorrow needs a clean, working room
                if (!r.checkIn().isAfter(today.plusDays(1)) && !r.checkIn().isBefore(today)) {
                    Room room = rooms.findById(r.room().id()).orElse(r.room());
                    if (room.status() == RoomStatus.DIRTY || room.status() == RoomStatus.MAINTENANCE) {
                        notReady.put(room.id(), room);
                    }
                }
            } else if (r.status() == ReservationStatus.CHECKED_IN) {
                if (r.checkOut().isBefore(today)) {
                    overstays.add(r);
                } else if (r.checkOut().equals(today)) {
                    dueOut.add(r);
                }
            }
        }
        java.util.Comparator<Reservation> byRoom = java.util.Comparator.comparing(r -> r.room().number());
        overdue.sort(java.util.Comparator.comparing(Reservation::checkIn).thenComparing(byRoom));
        arriving.sort(byRoom);
        dueOut.sort(byRoom);
        overstays.sort(byRoom);
        boolean closed = cashUps.closing(today).isPresent();
        List<Room> rooms = new ArrayList<>(notReady.values());
        rooms.sort(java.util.Comparator.comparing(Room::number));
        return new NightAudit(today, overdue, arriving, dueOut, overstays, List.copyOf(rooms), closed);
    }

    /**
     * Records that the guest never came. The room is free again from today, nothing is charged,
     * and a deposit already paid is kept (a no-show forfeits it; an admin can void it to refund).
     */
    public void markNoShow(Session session, long id) throws LodgeException {
        session.require(Permission.MANAGE_RESERVATIONS);
        Reservation r = reservation(id);
        if (r.status() != ReservationStatus.BOOKED) {
            throw new LodgeException("Only a booking whose guest has not arrived can be a no-show (this one is "
                    + r.status() + ").");
        }
        if (r.checkIn().isAfter(today())) {
            throw new LodgeException(r.guestName() + " is not due until " + r.checkIn().format(DAY)
                    + ". Cancel the booking instead if they are not coming.");
        }
        BigDecimal deposit = folio(id).summary().paid();
        reservations.setStatus(id, ReservationStatus.NO_SHOW);
        audit.record(session.username(), AuditAction.BOOKING_NO_SHOW, describe(r)
                + (deposit.signum() > 0 ? " (deposit " + Money.format(deposit) + " kept)" : ""));
    }

    /** Marks every booking that should have arrived before today as a no-show. Returns how many. */
    public int markOverdueNoShows(Session session) throws LodgeException {
        session.require(Permission.MANAGE_RESERVATIONS);
        List<Reservation> overdue = nightAudit(session).overdueArrivals();
        database.inTransaction(() -> {
            for (Reservation r : overdue) {
                try {
                    markNoShow(session, r.id());
                } catch (LodgeException e) {
                    throw new IllegalStateException(e);   // cannot happen: the audit just listed it
                }
            }
            return null;
        });
        return overdue.size();
    }

    /** What was taken on that day, by whom and how, and whether the day has been closed. */
    public CashUp cashUp(Session session, LocalDate date) throws LodgeException {
        session.require(Permission.CASH_UP);
        if (date == null) {
            throw new LodgeException("Choose a day.");
        }
        return new CashUp(date, cashUps.takings(date), cashUps.closing(date).orElse(null));
    }

    /**
     * Signs the day off after the cash drawer is counted. From then on no payment can be
     * taken on that day or voided from it, so the figures handed over stay true.
     * If the count does not match, a note must say why.
     */
    public DayClosing closeDay(Session session, LocalDate date, BigDecimal countedCash, String note)
            throws LodgeException {
        CashUp day = cashUp(session, date);
        if (day.isClosed()) {
            throw new LodgeException(date.format(DAY) + " is already closed.");
        }
        if (date.isAfter(today())) {
            throw new LodgeException("A day can only be closed once it has started.");
        }
        if (countedCash == null || countedCash.signum() < 0) {
            throw new LodgeException("Enter the cash counted in the drawer (0 if none).");
        }
        String why = clean(note);
        DayClosing closing = new DayClosing(date, session.username(), now(), day.expectedCash(),
                Money.scale(countedCash), why);
        if (closing.difference().signum() != 0 && why.length() < 3) {
            throw new LodgeException("The drawer is " + Money.format(closing.difference().abs())
                    + (closing.difference().signum() < 0 ? " short" : " over") + ". Add a note saying why.");
        }
        cashUps.close(closing);
        audit.record(session.username(), AuditAction.DAY_CLOSED, date + ": expected "
                + Money.format(closing.expectedCash()) + ", counted " + Money.format(closing.countedCash())
                + (why.isEmpty() ? "" : " (" + why + ")"));
        return closing;
    }

    /** Lets payments on a closed day be corrected. The day then has to be counted and closed again. */
    public void reopenDay(Session session, LocalDate date) throws LodgeException {
        session.require(Permission.REOPEN_DAY);
        if (date == null || cashUps.closing(date).isEmpty()) {
            throw new LodgeException("That day is not closed.");
        }
        cashUps.reopen(date);
        audit.record(session.username(), AuditAction.DAY_REOPENED, date.toString());
    }

    /** Refuses a change to a day whose takings have been counted and closed. */
    public void checkDayOpen(LocalDate date) throws LodgeException {
        DayClosing closed = cashUps.closing(date).orElse(null);
        if (closed != null) {
            throw new LodgeException("The takings for " + date.format(DAY) + " were closed by "
                    + closed.closedBy() + ". An admin must reopen the day first.");
        }
    }

    public void recordExport(Session session, String what) {
        session.require(Permission.VIEW_REPORTS);
        audit.record(session.username(), AuditAction.REPORT_EXPORTED, what);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private void checkForm(ReservationForm f, long ignoreId) throws LodgeException {
        if (f.guestName().length() < 2 || f.guestName().length() > 60 || !PersonName.isValid(f.guestName())) {
            throw new LodgeException("Enter the guest's name (letters, spaces, hyphens, apostrophes, initials like J. M.).");
        }
        if (!f.phone().matches(PHONE_PATTERN)) {
            throw new LodgeException("The phone number may contain digits, spaces, +, - and brackets only.");
        }
        if (f.company().length() > 60) {
            throw new LodgeException("The company name is too long (60 characters at most).");
        }
        if (f.companyPaysRoom() && f.company().isBlank()) {
            throw new LodgeException("Enter the company name, or untick \"Company pays the room\".");
        }
        if (f.room() == null) {
            throw new LodgeException("Choose a room.");
        }
        if (f.checkIn() == null || f.checkOut() == null) {
            throw new LodgeException("Choose the arrival and departure dates.");
        }
        if (!f.checkOut().isAfter(f.checkIn())) {
            throw new LodgeException("The departure date must be after the arrival date.");
        }
        long nights = java.time.temporal.ChronoUnit.DAYS.between(f.checkIn(), f.checkOut());
        if (nights > MAX_NIGHTS) {
            throw new LodgeException("A stay can be at most " + MAX_NIGHTS + " nights.");
        }
        if (reservations.roomTaken(f.room().id(), f.checkIn(), f.checkOut(), ignoreId)) {
            throw new LodgeException("Room " + f.room().number() + " is already booked for some of those nights.");
        }
    }

    /**
     * A room under maintenance cannot take new bookings. A Dirty room can: it is
     * cleaned before the guest arrives, and check-in still refuses it until then.
     * The status is re-read from the database, as the form may be out of date.
     */
    private void checkNotUnderMaintenance(Room room) throws LodgeException {
        Room current = rooms.findById(room.id()).orElseThrow(() -> new LodgeException("That room no longer exists."));
        if (current.status() == RoomStatus.MAINTENANCE) {
            throw new LodgeException("Room " + current.number() + " is under maintenance and cannot be booked. "
                    + "Choose another room, or mark it Available on the Rooms page first.");
        }
    }

    private static void checkRoomReady(Room room) throws LodgeException {
        switch (room.status()) {
            case AVAILABLE -> { }
            case DIRTY -> throw new LodgeException("Room " + room.number()
                    + " has not been cleaned yet. Housekeeping must mark it Available first.");
            case MAINTENANCE -> throw new LodgeException("Room " + room.number() + " is under maintenance.");
            case OCCUPIED -> throw new LodgeException("Room " + room.number() + " still has a guest in it.");
        }
    }

    private static void checkAmount(BigDecimal amount) throws LodgeException {
        if (amount == null || amount.signum() <= 0) {
            throw new LodgeException("Enter an amount above zero.");
        }
        if (amount.scale() > 2) {
            throw new LodgeException("Amounts have at most 2 decimal places.");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new LodgeException("That amount is too large. Check for a typo.");
        }
    }

    private static void checkPayer(Reservation r, Payer payer) throws LodgeException {
        if (payer == null) {
            throw new LodgeException("Choose who pays.");
        }
        if (payer == Payer.COMPANY && !r.hasCompany()) {
            throw new LodgeException("This booking has no company. Edit the booking to add one.");
        }
    }

    private static BigDecimal owedBy(FolioSummary s, Payer payer) {
        return s.payers().stream().filter(p -> p.payer() == payer).findFirst()
                .map(FolioSummary.PayerLine::balance).orElse(BigDecimal.ZERO);
    }

    private boolean hasCompanyLines(long reservationId) {
        return folios.findForReservation(reservationId).stream()
                .anyMatch(e -> !e.voided() && e.payer() == Payer.COMPANY);
    }

    private static Reservation withCheckOut(Reservation r, LocalDate checkOut) {
        return new Reservation(r.id(), r.guestName(), r.guestPhone(), r.company(), r.companyPaysRoom(), r.room(),
                r.checkIn(), checkOut, r.status(), r.nightlyRate(), r.vatPercent(), r.notes(), r.createdBy(),
                r.createdAt());
    }

    private static String describe(Reservation r) {
        return r.reference() + " " + r.guestName() + ", room " + r.room().number() + ", "
                + r.checkIn() + " to " + r.checkOut();
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    private static ReservationForm clean(ReservationForm f) {
        return new ReservationForm(clean(f.guestName()), clean(f.phone()), clean(f.company()),
                f.companyPaysRoom(), f.room(), f.checkIn(), f.checkOut(), clean(f.notes()));
    }
}
