package lodgebilling.view.pages;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import lodgebilling.controller.PageContext;
import lodgebilling.model.CleaningTask;
import lodgebilling.model.Money;
import lodgebilling.model.Reservation;
import lodgebilling.model.ReservationStatus;
import lodgebilling.model.Room;
import lodgebilling.model.RoomStatus;
import lodgebilling.model.RoomType;
import lodgebilling.model.security.Permission;
import lodgebilling.view.FormDialog;
import lodgebilling.view.Ui;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Room board for housekeeping and the front desk: one coloured tile per room.
 * The Show list (with counts) narrows the board to Available, Occupied, Dirty or
 * Maintenance rooms. Select a tile (click, or Tab + Space), then mark it clean,
 * dirty or under maintenance. Admins can also add, edit and delete rooms.
 * Above the board, housekeeping's list of rooms to clean puts the rooms a guest
 * arrives in soonest at the top.
 */
public class RoomsPage implements Page {

    private final PageContext ctx;
    private final FlowPane tiles = new FlowPane(12, 12);
    private final ToggleGroup group = new ToggleGroup();
    private final ComboBox<Filter> filter = new ComboBox<>();
    private final VBox root;
    // The rooms last read from the database; the filter only chooses which of them to show
    private List<Room> rooms = List.of();
    private Map<Long, Reservation> inHouse = Map.of();
    private RoomStatus shown;   // null = all rooms
    private final VBox cleaning = new VBox(6);

    public RoomsPage(PageContext ctx) {
        this.ctx = ctx;
        // Occupied is not offered: a room becomes Occupied only through check-in
        Button clean = Ui.compact("Mark clean", () -> setStatus(RoomStatus.AVAILABLE));
        Button dirty = Ui.compact("Mark dirty", () -> setStatus(RoomStatus.DIRTY));
        Button repair = Ui.compact("Maintenance", () -> setStatus(RoomStatus.MAINTENANCE));
        for (Button b : List.of(clean, dirty, repair)) {
            b.disableProperty().bind(group.selectedToggleProperty().isNull());
        }
        HBox actions = Ui.row(clean, dirty, repair, Ui.spacer());
        if (ctx.can(Permission.MANAGE_ROOMS)) {
            Button edit = Ui.compact("Edit room", this::editRoom);
            Button delete = Ui.compact("Delete room", this::deleteRoom);
            delete.getStyleClass().add("danger");
            edit.disableProperty().bind(group.selectedToggleProperty().isNull());
            delete.disableProperty().bind(group.selectedToggleProperty().isNull());
            actions.getChildren().addAll(Ui.compact("Add room", this::addRoom), edit, delete,
                    Ui.compact("Rates & VAT", this::editRates));
        }
        tiles.setRowValignment(VPos.TOP);
        tiles.setPadding(new Insets(4, 4, 12, 4));   // room for the tiles' shadow and selection glow
        ScrollPane scroll = new ScrollPane(tiles);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("main-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        filter.valueProperty().addListener((obs, old, f) -> {
            // The list is emptied while it is rebuilt; that is not a choice
            if (f != null && f.status() != shown) {
                shown = f.status();
                showTiles();
            }
        });
        Label show = Ui.fixed("Show");
        show.setLabelFor(filter);
        VBox cleaningCard = Ui.card("To clean, most urgent first", cleaning);
        root = new VBox(12, actions, cleaningCard, Ui.row(show, filter), scroll);
        root.setPadding(new Insets(16, 20, 16, 20));
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void refresh() {
        ctx.attempt(() -> {
            rooms = ctx.lodge().rooms();
            Map<Long, Reservation> guests = new java.util.HashMap<>();
            if (ctx.can(Permission.MANAGE_RESERVATIONS)) {   // guest names are not for housekeeping screens
                for (Reservation r : ctx.lodge().reservationsBetween(ctx.lodge().today(), ctx.lodge().today().plusDays(1))) {
                    if (r.status() == ReservationStatus.CHECKED_IN) guests.put(r.room().id(), r);
                }
            }
            inHouse = guests;
            buildFilters();
            showTiles();
            showCleaning(ctx.lodge().cleaningList(ctx.session()));
        });
    }

    /** Housekeeping's list: one line per room, with a Done button for rooms that can be cleaned now. */
    private void showCleaning(List<CleaningTask> tasks) {
        cleaning.getChildren().clear();
        if (tasks.isEmpty()) {
            Label none = new Label("Nothing to clean right now.");
            none.getStyleClass().add("hint");
            cleaning.getChildren().add(none);
            return;
        }
        LocalDate today = ctx.lodge().today();
        for (CleaningTask t : tasks) {
            Label room = Ui.fixed("Room " + t.room().number());
            room.getStyleClass().add("form-label");
            room.setMinWidth(90);
            Label when = Ui.chip(t.urgency(today), t.urgent(today) ? "status-dirty" : "status-available");
            Label what = new Label(t.afterCheckOut() ? "Guest leaves today: clean once they have checked out"
                    : t.room().type() + ", dirty");
            what.getStyleClass().add("hint");
            HBox line = Ui.row(room, when, what, Ui.spacer());
            if (!t.afterCheckOut()) {
                line.getChildren().add(Ui.compact("Done", () -> markCleaned(t.room())));
            }
            cleaning.getChildren().add(line);
        }
    }

    private void markCleaned(Room room) {
        if (ctx.attempt(() -> ctx.lodge().setRoomStatus(ctx.session(), room, RoomStatus.AVAILABLE))) {
            ctx.success("Room " + room.number() + " is clean and available.");
        }
        refresh();
    }

    /** One choice in the Show list: a status (null for all rooms) and how many rooms have it. */
    private record Filter(RoomStatus status, int count) {
        @Override
        public String toString() {
            return (status == null ? "All rooms" : status.toString()) + " (" + count + ")";
        }
    }

    /** All rooms, then each status, with up-to-date counts. The chosen one stays chosen. */
    private void buildFilters() {
        Map<RoomStatus, Integer> byStatus = new EnumMap<>(RoomStatus.class);
        for (RoomStatus s : RoomStatus.values()) byStatus.put(s, 0);
        for (Room room : rooms) byStatus.merge(room.status(), 1, Integer::sum);

        List<Filter> choices = new java.util.ArrayList<>();
        choices.add(new Filter(null, rooms.size()));
        for (RoomStatus s : RoomStatus.values()) {
            choices.add(new Filter(s, byStatus.get(s)));
        }
        filter.getItems().setAll(choices);
        filter.setValue(choices.stream().filter(f -> f.status() == shown).findFirst().orElseThrow());
    }

    /** The tiles for the rooms the filter lets through. A hidden room cannot stay selected. */
    private void showTiles() {
        Long selectedId = group.getSelectedToggle() == null ? null : (Long) group.getSelectedToggle().getUserData();
        tiles.getChildren().clear();
        group.getToggles().clear();
        for (Room room : rooms) {
            if (shown == null || room.status() == shown) {
                tiles.getChildren().add(tile(room, inHouse.get(room.id()), selectedId));
            }
        }
        if (tiles.getChildren().isEmpty()) {
            Label none = new Label("No rooms are " + shown.toString().toLowerCase() + " right now.");
            none.getStyleClass().add("hint");
            tiles.getChildren().add(none);
        }
    }

    private Node tile(Room room, Reservation guest, Long selectedId) {
        Label number = new Label(room.number());
        number.getStyleClass().add("tile-number");
        Label type = new Label(room.type() + "  " + Money.format(ctx.lodge().rateCard().rateFor(room.type())));
        type.getStyleClass().add("tile-type");
        Label status = Ui.chip(room.status().toString(), room.status().styleClass());
        VBox box = new VBox(4, number, type, status);
        if (guest != null) {
            Label g = new Label(guest.guestName() + ", until " + guest.checkOut().format(Ui.DAY));
            g.getStyleClass().add("hint");
            g.setWrapText(true);
            box.getChildren().add(g);
        }
        box.setAlignment(Pos.TOP_LEFT);
        ToggleButton t = new ToggleButton();
        t.setGraphic(box);
        t.setUserData(room.id());
        t.setToggleGroup(group);
        t.getStyleClass().addAll("room-tile", "tile-" + room.status().name().toLowerCase());
        // Tall enough for the guest line, so every tile in a row is the same size
        t.setPrefSize(184, 132);
        if (selectedId != null && selectedId == room.id()) {
            t.setSelected(true);
        }
        return t;
    }

    private Room selectedRoom() {
        if (group.getSelectedToggle() == null) {
            ctx.error("Select a room tile first.");
            return null;
        }
        long id = (Long) group.getSelectedToggle().getUserData();
        return ctx.lodge().rooms().stream().filter(r -> r.id() == id).findFirst().orElse(null);
    }

    private void setStatus(RoomStatus status) {
        Room room = selectedRoom();
        if (room != null && ctx.attempt(() -> ctx.lodge().setRoomStatus(ctx.session(), room, status))) {
            ctx.success("Room " + room.number() + " is now " + status + ".");
        }
        refresh();
    }

    private void addRoom() {
        FormDialog form = new FormDialog(ctx.owner(), ctx.theme(), "Add room", "Add a room to the lodge",
                "Add room", "Cancel");
        TextField number = form.add("Room number", new TextField());
        ComboBox<RoomType> type = form.add("Type", new ComboBox<>(FXCollections.observableArrayList(RoomType.values())));
        type.setValue(RoomType.STANDARD);
        form.onSubmit(() -> ctx.lodge().addRoom(ctx.session(), number.getText(), type.getValue()));
        if (form.showAndWait()) {
            ctx.success("Room " + number.getText().trim() + " added.");
        }
        refresh();
    }

    private void editRoom() {
        Room room = selectedRoom();
        if (room == null) {
            return;
        }
        FormDialog form = new FormDialog(ctx.owner(), ctx.theme(), "Edit room", "Room " + room.number(),
                "Save room", "Cancel");
        TextField number = form.add("Room number", new TextField(room.number()));
        ComboBox<RoomType> type = form.add("Type", new ComboBox<>(FXCollections.observableArrayList(RoomType.values())));
        type.setValue(room.type());
        form.addNote("Bookings already made keep the price they were booked at.");
        form.onSubmit(() -> ctx.lodge().updateRoom(ctx.session(), room, number.getText(), type.getValue()));
        if (form.showAndWait()) {
            ctx.success("Room " + number.getText().trim() + " saved.");
        }
        refresh();
    }

    private void deleteRoom() {
        Room room = selectedRoom();
        if (room == null || !Ui.confirm(ctx.owner(), ctx.theme(), "Delete room " + room.number() + "?",
                "The room is removed from the lodge. This cannot be undone.", "Delete room")) {
            return;
        }
        if (ctx.attempt(() -> ctx.lodge().deleteRoom(ctx.session(), room))) {
            ctx.success("Room " + room.number() + " deleted.");
        }
        refresh();
    }

    private void editRates() {
        FormDialog form = new FormDialog(ctx.owner(), ctx.theme(), "Rates & VAT", "Prices per night (VAT excluded)",
                "Save prices", "Cancel");
        Map<RoomType, TextField> fields = new EnumMap<>(RoomType.class);
        for (RoomType t : RoomType.values()) {
            fields.put(t, form.add(t + " in K", new TextField(ctx.lodge().rateCard().rateFor(t).toPlainString())));
        }
        TextField vat = form.add("VAT %", new TextField(
                ctx.lodge().rateCard().vatPercent().stripTrailingZeros().toPlainString()));
        form.addNote("New prices apply to new bookings and receipts only. Existing bookings keep the "
                + "price they were booked at.");
        form.onSubmit(() -> {
            Map<RoomType, BigDecimal> rates = new EnumMap<>(RoomType.class);
            for (RoomType t : RoomType.values()) {
                rates.put(t, Ui.amount(fields.get(t).getText()));
            }
            String v = vat.getText().trim();
            if (!v.matches("\\d{1,2}(\\.\\d{1,2})?")) {
                throw new lodgebilling.model.service.LodgeException("Enter VAT as a number, e.g. 16.");
            }
            ctx.lodge().saveRates(ctx.session(), rates, new BigDecimal(v));
        });
        if (form.showAndWait()) {
            ctx.success("Prices and VAT saved.");
        }
        refresh();
    }
}
