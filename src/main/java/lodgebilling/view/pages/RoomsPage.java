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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Room board for housekeeping and the front desk: one coloured tile per room.
 * Select a tile (click, or Tab + Space), then mark it clean, dirty or under maintenance.
 */
public class RoomsPage implements Page {

    private final PageContext ctx;
    private final FlowPane tiles = new FlowPane(12, 12);
    private final ToggleGroup group = new ToggleGroup();
    private final HBox counts = new HBox(8);
    private final VBox root;

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
            actions.getChildren().addAll(Ui.compact("Add room", this::addRoom),
                    Ui.compact("Rates & VAT", this::editRates));
        }
        tiles.setRowValignment(VPos.TOP);
        tiles.setPadding(new Insets(4, 4, 12, 4));   // room for the tiles' shadow and selection glow
        ScrollPane scroll = new ScrollPane(tiles);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("main-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        root = new VBox(12, actions, counts, scroll);
        root.setPadding(new Insets(16, 20, 16, 20));
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void refresh() {
        Long selectedId = group.getSelectedToggle() == null ? null : (Long) group.getSelectedToggle().getUserData();
        ctx.attempt(() -> {
            List<Room> rooms = ctx.lodge().rooms();
            Map<Long, Reservation> inHouse = new java.util.HashMap<>();
            if (ctx.can(Permission.MANAGE_RESERVATIONS)) {   // guest names are not for housekeeping screens
                for (Reservation r : ctx.lodge().reservationsBetween(ctx.lodge().today(), ctx.lodge().today().plusDays(1))) {
                    if (r.status() == ReservationStatus.CHECKED_IN) inHouse.put(r.room().id(), r);
                }
            }
            Map<RoomStatus, Integer> byStatus = new EnumMap<>(RoomStatus.class);
            for (RoomStatus s : RoomStatus.values()) byStatus.put(s, 0);
            tiles.getChildren().clear();
            group.getToggles().clear();
            for (Room room : rooms) {
                byStatus.merge(room.status(), 1, Integer::sum);
                tiles.getChildren().add(tile(room, inHouse.get(room.id()), selectedId));
            }
            counts.getChildren().clear();
            for (RoomStatus s : RoomStatus.values()) {
                counts.getChildren().add(Ui.chip(s + ": " + byStatus.get(s), s.styleClass()));
            }
        });
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
                rates.put(t, FolioWindow.parseAmount(fields.get(t).getText()));
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
