package lodgebilling.view.pages;

import javafx.collections.FXCollections;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import lodgebilling.controller.PageContext;
import lodgebilling.model.Bill;
import lodgebilling.model.FolioCalculator;
import lodgebilling.model.GuestHistory;
import lodgebilling.model.Money;
import lodgebilling.model.Payer;
import lodgebilling.model.PaymentLine;
import lodgebilling.model.PaymentMethod;
import lodgebilling.model.Reservation;
import lodgebilling.model.ReservationForm;
import lodgebilling.model.ReservationStatus;
import lodgebilling.model.Room;
import lodgebilling.model.RoomStatus;
import lodgebilling.model.security.Permission;
import lodgebilling.model.service.LodgeService;
import lodgebilling.view.FormDialog;
import lodgebilling.view.Ui;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * New booking, walk-in, or edit. The room list only offers rooms free for the
 * chosen dates, and a live estimate shows what the stay will cost. A walk-in can
 * pay in the same window: the amount starts at the estimate.
 */
public final class ReservationDialog {

    public enum Mode { BOOK, WALK_IN, EDIT }

    private ReservationDialog() { }

    /**
     * @param existing the booking being edited (EDIT mode), else null
     * @param room     a room to pre-select (e.g. clicked on the tape chart), may be null
     * @param date     an arrival date to pre-fill, may be null
     * @return the saved booking, if the user saved one
     */
    public static Optional<Reservation> show(PageContext ctx, Mode mode, Reservation existing, Room room,
                                             LocalDate date) {
        LodgeService lodge = ctx.lodge();
        LocalDate today = lodge.today();
        String title = switch (mode) {
            case BOOK -> "New booking";
            case WALK_IN -> "Walk-in guest";
            case EDIT -> "Edit booking " + existing.reference();
        };
        FormDialog form = new FormDialog(ctx.owner(), ctx.theme(), title,
                mode == Mode.WALK_IN ? "Book from today and check the guest in now" : title,
                mode == Mode.WALK_IN ? "Check in" : "Save booking", "Cancel");

        TextField guest = form.add("Guest name", new TextField());
        guest.setPromptText("e.g., John Phiri");
        TextField phone = form.add("Phone", new TextField());
        phone.setPromptText("Optional, e.g. +260 97 123 4567");
        // A returning guest is recognised by phone: their details are filled in and their history shown
        Label known = form.add("", new Label());
        known.setWrapText(true);
        known.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        known.managedProperty().bind(known.visibleProperty());
        known.setVisible(false);
        TextField company = form.add("Company", new TextField());
        company.setPromptText("Optional, for business guests");
        CheckBox companyPays = form.add("", new CheckBox("Company pays the room"));
        DatePicker arrive = form.add("Arrival", new DatePicker());
        DatePicker leave = form.add("Departure", new DatePicker());
        ComboBox<Room> rooms = form.add("Room", new ComboBox<>());
        rooms.setPromptText("Choose dates first");
        Label estimate = form.add("Estimate", new Label("-"));
        estimate.setWrapText(true);
        estimate.getStyleClass().add("price-amount");
        estimate.setMaxWidth(260);
        estimate.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        // A walk-in pays as they check in (in full, a deposit, or 0 to pay later)
        boolean takesPayment = mode == Mode.WALK_IN && ctx.can(Permission.POST_FOLIO);
        TextField paidNow = new TextField();
        ComboBox<PaymentMethod> method = new ComboBox<>(FXCollections.observableArrayList(PaymentMethod.values()));
        boolean[] paidTyped = {false};
        if (takesPayment) {
            form.add("Paid now (K)", paidNow);
            paidNow.setOnKeyTyped(e -> paidTyped[0] = true);   // stop following the estimate once typed in
            form.add("Method", method);
            method.setValue(PaymentMethod.CASH);
        }
        TextArea notes = form.add("Notes", Ui.notesArea());

        if (existing != null) {
            guest.setText(existing.guestName());
            phone.setText(existing.guestPhone());
            company.setText(existing.company());
            companyPays.setSelected(existing.companyPaysRoom());
            arrive.setValue(existing.checkIn());
            leave.setValue(existing.checkOut());
            notes.setText(existing.notes());
            if (existing.status() == ReservationStatus.CHECKED_IN) {
                arrive.setDisable(true);   // the guest is already here
                rooms.setDisable(true);
            }
        } else {
            LocalDate start = mode == Mode.WALK_IN ? today : (date != null ? date : today);
            arrive.setValue(start);
            leave.setValue(start.plusDays(1));
            arrive.setDisable(mode == Mode.WALK_IN);
        }
        companyPays.disableProperty().bind(company.textProperty().isEmpty());
        if (existing == null) {
            phone.textProperty().addListener((o, a, typed) -> showHistory(ctx, typed, known, guest, company));
        }

        long ignoreId = existing == null ? 0 : existing.id();
        Room wanted = existing != null ? existing.room() : room;
        Runnable reloadRooms = () -> {
            Room selected = rooms.getValue() != null ? rooms.getValue() : wanted;
            List<Room> free = lodge.freeRooms(arrive.getValue(), leave.getValue(), ignoreId);
            if (mode == Mode.WALK_IN) {
                // A walk-in moves in now, so only a clean, ready room will do
                free = free.stream().filter(r -> r.status() == RoomStatus.AVAILABLE).toList();
            }
            rooms.setItems(FXCollections.observableArrayList(free));
            rooms.setPromptText(free.isEmpty() ? "No room free for these dates" : "Choose a room");
            if (selected != null) {
                free.stream().filter(r -> r.id() == selected.id()).findFirst().ifPresent(rooms::setValue);
            }
        };
        Runnable updateEstimate = () -> {
            estimate.setText(estimate(lodge, existing, rooms.getValue(), arrive.getValue(), leave.getValue()));
            if (takesPayment && !paidTyped[0]) {
                BigDecimal total = total(lodge, existing, rooms.getValue(), arrive.getValue(), leave.getValue());
                paidNow.setText(total == null || companyPays.isSelected() ? "" : total.toPlainString());
            }
        };
        companyPays.selectedProperty().addListener((o, a, b) -> updateEstimate.run());
        arrive.valueProperty().addListener((o, a, b) -> {
            if (b != null && leave.getValue() != null && !leave.getValue().isAfter(b)) {
                leave.setValue(b.plusDays(1));   // keep departure after arrival
            }
            reloadRooms.run();
            updateEstimate.run();
        });
        leave.valueProperty().addListener((o, a, b) -> { reloadRooms.run(); updateEstimate.run(); });
        rooms.valueProperty().addListener((o, a, b) -> updateEstimate.run());
        reloadRooms.run();
        updateEstimate.run();

        Reservation[] saved = new Reservation[1];
        form.onSubmit(() -> {
            ReservationForm f = new ReservationForm(guest.getText(), phone.getText(), company.getText(),
                    companyPays.isSelected() && !company.getText().isBlank(), rooms.getValue(),
                    arrive.getValue(), leave.getValue(), notes.getText());
            PaymentLine payment = null;
            if (takesPayment && !paidNow.getText().isBlank()) {
                BigDecimal amount = Ui.amount(paidNow.getText());
                if (amount.signum() > 0) {
                    payment = new PaymentLine(f.companyPaysRoom() ? Payer.COMPANY : Payer.GUEST, method.getValue(),
                            amount, "");
                }
            }
            PaymentLine pay = payment;
            saved[0] = switch (mode) {
                case BOOK -> lodge.book(ctx.session(), f);
                case WALK_IN -> lodge.walkIn(ctx.session(), f, pay);
                case EDIT -> lodge.update(ctx.session(), existing.id(), f);
            };
        });
        return form.showAndWait() ? Optional.ofNullable(saved[0]) : Optional.empty();
    }

    /** Fills in a returning guest's name and company (if not typed yet) and says when they last stayed. */
    private static void showHistory(PageContext ctx, String phone, Label known, TextField guest, TextField company) {
        GuestHistory history = ctx.lodge().guestHistory(ctx.session(), phone);
        if (history.isEmpty()) {
            known.setVisible(false);
            return;
        }
        Reservation last = history.last();
        if (guest.getText().isBlank()) {
            guest.setText(last.guestName());
        }
        if (company.getText().isBlank()) {
            company.setText(last.company());
        }
        int count = history.stays().size();
        String text = "Returning guest: " + count + (count == 1 ? " earlier stay" : " earlier stays")
                + ", last in room " + last.room().number() + " from " + last.checkIn().format(Ui.DATE) + ".";
        known.getStyleClass().setAll("label", "status");
        if (history.owed().signum() > 0) {
            text += " Still owes " + Money.format(history.owed()) + " from before.";
            known.getStyleClass().add("error");
        }
        known.setText(text);
        known.setVisible(true);
    }

    private static String estimate(LodgeService lodge, Reservation existing, Room room, LocalDate in, LocalDate out) {
        BigDecimal total = total(lodge, existing, room, in, out);
        if (total == null) {
            return "-";
        }
        int nights = (int) ChronoUnit.DAYS.between(in, out);
        BigDecimal vat = existing != null ? existing.vatPercent() : lodge.rateCard().vatPercent();
        return nights + " night(s) x " + Money.format(rate(lodge, existing, room))
                + (nights >= Bill.DISCOUNT_MIN_NIGHTS ? " - " + Bill.discountPercentLabel() + " long-stay discount" : "")
                + " + " + vat.stripTrailingZeros().toPlainString() + "% VAT = " + Money.format(total);
    }

    /** The room nights with discount and VAT, or null until a room and valid dates are chosen. */
    private static BigDecimal total(LodgeService lodge, Reservation existing, Room room, LocalDate in, LocalDate out) {
        if (room == null || in == null || out == null || !out.isAfter(in)) {
            return null;
        }
        int nights = (int) ChronoUnit.DAYS.between(in, out);
        BigDecimal vat = existing != null ? existing.vatPercent() : lodge.rateCard().vatPercent();
        return FolioCalculator.estimate(rate(lodge, existing, room), nights, vat);
    }

    /** An existing booking keeps the price it was made at. */
    private static BigDecimal rate(LodgeService lodge, Reservation existing, Room room) {
        return existing != null ? existing.nightlyRate() : lodge.rateCard().rateFor(room.type());
    }
}
