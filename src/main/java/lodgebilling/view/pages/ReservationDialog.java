package lodgebilling.view.pages;

import javafx.collections.FXCollections;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import lodgebilling.model.Bill;
import lodgebilling.model.Money;
import lodgebilling.model.Reservation;
import lodgebilling.model.ReservationForm;
import lodgebilling.model.ReservationStatus;
import lodgebilling.model.Room;
import lodgebilling.model.RoomStatus;
import lodgebilling.service.LodgeService;
import lodgebilling.view.FormDialog;
import lodgebilling.view.Ui;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * New booking, walk-in, or edit. The room list only offers rooms free for the
 * chosen dates, and a live estimate shows what the stay will cost.
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
        Runnable updateEstimate = () -> estimate.setText(estimate(lodge, existing, rooms.getValue(),
                arrive.getValue(), leave.getValue()));
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
            saved[0] = switch (mode) {
                case BOOK -> lodge.book(ctx.session(), f);
                case WALK_IN -> lodge.walkIn(ctx.session(), f);
                case EDIT -> lodge.update(ctx.session(), existing.id(), f);
            };
        });
        return form.showAndWait() ? Optional.ofNullable(saved[0]) : Optional.empty();
    }

    private static String estimate(LodgeService lodge, Reservation existing, Room room, LocalDate in, LocalDate out) {
        if (room == null || in == null || out == null || !out.isAfter(in)) {
            return "-";
        }
        int nights = (int) ChronoUnit.DAYS.between(in, out);
        BigDecimal rate = existing != null ? existing.nightlyRate() : lodge.rateCard().rateFor(room.type());
        BigDecimal vat = existing != null ? existing.vatPercent() : lodge.rateCard().vatPercent();
        Bill b = new Bill("", "", room.type(), rate, nights, BigDecimal.ZERO, BigDecimal.ZERO, "", null, "");
        BigDecimal net = b.getRoomCharge().subtract(b.getDiscount());
        BigDecimal withVat = Money.scale(net.add(net.multiply(vat).divide(new BigDecimal("100"))));
        return nights + " night(s) x " + Money.format(rate)
                + (b.getDiscount().signum() > 0 ? " - " + Bill.discountPercentLabel() + " long-stay discount" : "")
                + " + " + vat.stripTrailingZeros().toPlainString() + "% VAT = " + Money.format(withVat);
    }
}
