package lodgebilling.service;

import lodgebilling.data.AuditLog;
import lodgebilling.data.AuditLog.Action;
import lodgebilling.data.Database;
import lodgebilling.data.FolioRepository;
import lodgebilling.data.ReportRepository;
import lodgebilling.data.ReportRepository.DailyStat;
import lodgebilling.data.ReservationRepository;
import lodgebilling.data.RoomRepository;
import lodgebilling.data.SettingsRepository;
import lodgebilling.model.ChargeCategory;
import lodgebilling.model.DashboardData;
import lodgebilling.model.FolioCalculator;
import lodgebilling.model.FolioEntry;
import lodgebilling.model.FolioSummary;
import lodgebilling.model.Money;
import lodgebilling.model.Payer;
import lodgebilling.model.PaymentMethod;
import lodgebilling.model.RateCard;
import lodgebilling.model.Reservation;
import lodgebilling.model.ReservationForm;
import lodgebilling.model.ReservationStatus;
import lodgebilling.model.Room;
import lodgebilling.model.RoomStatus;
import lodgebilling.model.RoomType;
import lodgebilling.security.Permission;
import lodgebilling.security.Session;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    private static final String NAME_PATTERN = "\\p{L}+([ '’\\-.]\\p{L}+)*";
    private static final String PHONE_PATTERN = "[+0-9 ()\\-]{0,20}";

    /** A record of what a stay costs, plus its lines, for the folio window. */
    public record Folio(Reservation stay, List<FolioEntry> entries, FolioSummary summary) { }

    private final Database database;
    private final RoomRepository rooms;
    private final ReservationRepository reservations;
    private final FolioRepository folios;
    private final SettingsRepository settings;
    private final ReportRepository reports;
    private final AuditLog audit;
    private final Clock clock;
    private final RateCard rateCard;

    public LodgeService(Database database, AuditLog audit, Clock clock) {
        this.database = database;
        this.rooms = new RoomRepository(database);
        this.reservations = new ReservationRepository(database);
        this.folios = new FolioRepository(database);
        this.settings = new SettingsRepository(database);
        this.reports = new ReportRepository(database);
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
                settings.put(SettingsRepository.VAT_PERCENT, DEFAULT_VAT.toPlainString());
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
            settings.put(SettingsRepository.VAT_PERCENT, vatPercent.stripTrailingZeros().toPlainString());
            return null;
        });
        rateCard.replaceWith(new RateCard(newRates, vatPercent));
        StringBuilder detail = new StringBuilder();
        newRates.forEach((t, r) -> detail.append(t).append(' ').append(Money.format(r)).append(", "));
        audit.record(session.username(), Action.RATES_CHANGED, detail + "VAT " + vatPercent + "%");
    }

    public List<Room> rooms() {
        return rooms.findAll();
    }

    public Room addRoom(Session session, String number, RoomType type) throws LodgeException {
        session.require(Permission.MANAGE_ROOMS);
        String n = number == null ? "" : number.trim();
        if (!n.matches("[A-Za-z0-9\\-]{1,10}")) {
            throw new LodgeException("Room numbers are 1 to 10 letters, digits or hyphens (e.g. 104 or A-2).");
        }
        if (type == null) {
            throw new LodgeException("Choose a room type.");
        }
        if (rooms.numberExists(n)) {
            throw new LodgeException("Room " + n + " already exists.");
        }
        Room room = rooms.insert(n, type);
        audit.record(session.username(), Action.ROOM_ADDED, n + " (" + type + ")");
        return room;
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
        audit.record(session.username(), Action.ROOM_STATUS, current.number() + ": " + current.status() + " -> " + status);
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
        for (Room r : rooms.findAll()) {
            if (r.status() == RoomStatus.MAINTENANCE && r.id() != keepRoomId) {
                continue;
            }
            if (!reservations.roomTaken(r.id(), checkIn, checkOut, ignoreReservationId)) {
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
        audit.record(session.username(), Action.BOOKING_CREATED, describe(r));
        return r;
    }

    /** A guest arriving without a booking: book from today and check in straight away. */
    public Reservation walkIn(Session session, ReservationForm form) throws LodgeException {
        session.require(Permission.MANAGE_RESERVATIONS);
        ReservationForm f = clean(form);
        if (!today().equals(f.checkIn())) {
            throw new LodgeException("A walk-in starts today.");
        }
        checkRoomReady(f.room());
        Reservation booked = book(session, f);
        return checkIn(session, booked.id());
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
        audit.record(session.username(), Action.BOOKING_UPDATED, describe(r));
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
        audit.record(session.username(), Action.BOOKING_CANCELLED, describe(r));
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
        audit.record(session.username(), Action.CHECK_IN, describe(r));
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
        // Leaving before the booked date: bill only the nights used (at least one)
        LocalDate leaving = today().isAfter(r.checkIn()) ? today() : r.checkIn().plusDays(1);
        LocalDate newCheckOut = leaving.isBefore(r.checkOut()) ? leaving : r.checkOut();
        Reservation adjusted = withCheckOut(r, newCheckOut);
        FolioSummary summary = FolioCalculator.summarise(adjusted, folios.findForReservation(id));

        if (summary.balance().signum() > 0) {
            if (!force) {
                throw new BalanceDueException(r.guestName(), summary.balance());
            }
            session.require(Permission.FORCE_CHECKOUT);
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
            audit.record(session.username(), Action.FORCED_CHECKOUT,
                    describe(r) + ", left owing " + Money.format(summary.balance()));
        } else {
            audit.record(session.username(), Action.CHECK_OUT, describe(r));
        }
        return folio(id);
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
        Map<Long, List<FolioEntry>> all = folios.findAllGrouped();
        Map<Long, FolioSummary> out = new java.util.HashMap<>();
        for (Reservation r : stays) {
            out.put(r.id(), FolioCalculator.summarise(r, all.getOrDefault(r.id(), List.of())));
        }
        return out;
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
        audit.record(session.username(), Action.CHARGE_POSTED,
                r.reference() + " " + category + " " + Money.format(amount) + " to " + r.payerName(payer));
        return e;
    }

    /** One payment. Refused if it is more than that payer owes (stops typing 10000 for 1000). */
    public FolioEntry recordPayment(Session session, long reservationId, PaymentMethod method, BigDecimal amount,
                                    Payer payer, String reference) throws LodgeException {
        session.require(Permission.POST_FOLIO);
        Folio folio = folio(reservationId);
        Reservation r = folio.stay();
        if (r.status() == ReservationStatus.CANCELLED) {
            throw new LodgeException("This booking was cancelled.");
        }
        Objects.requireNonNull(method, "method");
        checkAmount(amount);
        checkPayer(r, payer);
        BigDecimal owed = owedBy(folio.summary(), payer);
        if (amount.compareTo(owed) > 0) {
            throw new LodgeException(r.payerName(payer) + " owes only " + Money.format(owed) + ".");
        }
        FolioEntry e = folios.insert(reservationId, FolioEntry.Kind.PAYMENT, method.name(), clean(reference),
                Money.scale(amount), payer, now(), session.username());
        audit.record(session.username(), Action.PAYMENT_RECORDED,
                r.reference() + " " + method + " " + Money.format(amount) + " from " + r.payerName(payer));
        return e;
    }

    /** Split payment: the guest and the company each pay their share at once, all or nothing. */
    public void recordSplitPayment(Session session, long reservationId, PaymentMethod guestMethod,
                                   BigDecimal guestAmount, PaymentMethod companyMethod, BigDecimal companyAmount,
                                   String reference) throws LodgeException {
        session.require(Permission.POST_FOLIO);
        Folio folio = folio(reservationId);
        if (!folio.stay().hasCompany()) {
            throw new LodgeException("Add a company to the booking to split the bill.");
        }
        boolean guestPays = guestAmount != null && guestAmount.signum() > 0;
        boolean companyPays = companyAmount != null && companyAmount.signum() > 0;
        if (!guestPays && !companyPays) {
            throw new LodgeException("Enter at least one amount.");
        }
        try {
            database.inTransaction(() -> {
                try {
                    if (guestPays) {
                        recordPayment(session, reservationId, guestMethod, guestAmount, Payer.GUEST, reference);
                    }
                    if (companyPays) {
                        recordPayment(session, reservationId, companyMethod, companyAmount, Payer.COMPANY, reference);
                    }
                } catch (LodgeException e) {
                    throw new Rollback(e);   // undo the first payment if the second is refused
                }
                return null;
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
        folios.voidEntry(entry.id(), why);
        audit.record(session.username(), Action.ENTRY_VOIDED, "BK" + String.format("%04d", entry.reservationId())
                + " " + entry.kind() + " " + Money.format(entry.amount()) + ": " + why);
    }

    // =====================================================================
    // Dashboard and reports
    // =====================================================================

    public DashboardData dashboard(Session session) {
        session.require(Permission.VIEW_DASHBOARD);
        LocalDate today = today();
        List<Reservation> arrivals = new ArrayList<>();
        List<Reservation> departures = new ArrayList<>();
        int inHouse = 0;
        BigDecimal outstanding = BigDecimal.ZERO;
        Map<Long, List<FolioEntry>> allEntries = folios.findAllGrouped();
        for (Reservation r : reservations.findAll()) {
            if (r.status() == ReservationStatus.BOOKED && !r.checkIn().isAfter(today)) {
                arrivals.add(r);
            }
            if (r.status() == ReservationStatus.CHECKED_IN) {
                inHouse++;
                if (!r.checkOut().isAfter(today)) {
                    departures.add(r);
                }
            }
            if (r.status() == ReservationStatus.CHECKED_IN || r.status() == ReservationStatus.CHECKED_OUT) {
                BigDecimal bal = FolioCalculator.summarise(r, allEntries.getOrDefault(r.id(), List.of())).balance();
                if (bal.signum() > 0) {
                    outstanding = outstanding.add(bal);
                }
            }
        }
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
        Map<Long, List<FolioEntry>> allEntries = folios.findAllGrouped();
        List<Folio> owing = new ArrayList<>();
        for (Reservation r : reservations.findAll()) {
            if (r.status() == ReservationStatus.CHECKED_IN || r.status() == ReservationStatus.CHECKED_OUT) {
                List<FolioEntry> entries = allEntries.getOrDefault(r.id(), List.of());
                FolioSummary s = FolioCalculator.summarise(r, entries);
                if (s.balance().signum() > 0) {
                    owing.add(new Folio(r, entries, s));
                }
            }
        }
        owing.sort((a, b) -> b.summary().balance().compareTo(a.summary().balance()));
        return owing;
    }

    public void recordExport(Session session, String what) {
        session.require(Permission.VIEW_REPORTS);
        audit.record(session.username(), Action.REPORT_EXPORTED, what);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private void checkForm(ReservationForm f, long ignoreId) throws LodgeException {
        if (f.guestName().length() < 2 || f.guestName().length() > 60 || !f.guestName().matches(NAME_PATTERN)) {
            throw new LodgeException("Enter the guest's name (letters, spaces, hyphens, apostrophes).");
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
                r.checkIn(), checkOut, r.status(), r.nightlyRate(), r.vatPercent(), r.notes(), r.createdBy());
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
