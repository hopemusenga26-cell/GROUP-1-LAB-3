package lodgebilling.view.pages;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import lodgebilling.data.AuditLog;
import lodgebilling.model.DashboardData;
import lodgebilling.model.Money;
import lodgebilling.model.Reservation;
import lodgebilling.model.RoomStatus;
import lodgebilling.security.Permission;
import lodgebilling.view.Ui;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** The front-desk command centre: today's numbers, who is arriving and leaving, and recent activity. */
public class DashboardPage implements Page {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM HH:mm")
            .withZone(ZoneId.systemDefault());
    // Account events are only shown to admins
    private static final Set<AuditLog.Action> ACCOUNT_EVENTS = EnumSet.of(AuditLog.Action.LOGIN,
            AuditLog.Action.LOGIN_FAILED, AuditLog.Action.LOGOUT, AuditLog.Action.ACCOUNT_LOCKED,
            AuditLog.Action.SETUP, AuditLog.Action.USER_CREATED, AuditLog.Action.USER_ENABLED,
            AuditLog.Action.USER_DISABLED, AuditLog.Action.ROLE_CHANGED, AuditLog.Action.PASSWORD_RESET,
            AuditLog.Action.PASSWORD_CHANGED);

    private final PageContext ctx;
    private final HBox metrics = new HBox(14);
    private final TableView<Reservation> arrivals = stayTable("No arrivals left today.");
    private final TableView<Reservation> departures = stayTable("No departures left today.");
    private final FlowPane roomChips = new FlowPane(8, 8);
    private final VBox activity = new VBox(6);
    private final ScrollPane root;

    public DashboardPage(PageContext ctx) {
        this.ctx = ctx;
        // "Due" rather than "today": late arrivals and departures stay on the lists
        VBox arrivalsCard = Ui.card("Arrivals due", arrivals);
        VBox departuresCard = Ui.card("Departures due", departures);
        HBox.setHgrow(arrivalsCard, Priority.ALWAYS);
        HBox.setHgrow(departuresCard, Priority.ALWAYS);
        arrivalsCard.setPrefWidth(400);
        departuresCard.setPrefWidth(400);

        VBox rooms = Ui.card("Rooms", roomChips);
        VBox feed = Ui.card("Recent activity", activity);
        HBox.setHgrow(feed, Priority.ALWAYS);
        rooms.setPrefWidth(360);
        rooms.setMaxHeight(Region.USE_PREF_SIZE);   // just the chips, not stretched to the activity list

        VBox content = new VBox(14, metrics, new HBox(14, arrivalsCard, departuresCard),
                new HBox(14, rooms, feed));
        content.setPadding(new Insets(16, 20, 20, 20));
        root = new ScrollPane(content);
        root.setFitToWidth(true);
        root.getStyleClass().add("main-scroll");
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void refresh() {
        ctx.attempt(() -> {
            DashboardData d = ctx.lodge().dashboard(ctx.session());
            // Arrivals and departures are counted by their tables below, so they get no tile
            metrics.getChildren().setAll(
                    Ui.metric("Occupancy (" + d.inHouse() + " of " + d.totalRooms() + " rooms)",
                            String.format("%.0f%%", d.occupancyPercent()), null),
                    Ui.metric("Outstanding balances", Money.format(d.outstanding()),
                            d.outstanding().signum() > 0 ? "metric-overdue" : null),
                    Ui.metric("Payments today", Money.format(d.paymentsToday()), null));
            arrivals.setItems(FXCollections.observableArrayList(d.arrivals()));
            departures.setItems(FXCollections.observableArrayList(d.departures()));
            roomChips.getChildren().clear();
            for (RoomStatus s : RoomStatus.values()) {
                roomChips.getChildren().add(Ui.chip(s + ": " + d.roomsByStatus().get(s), s.styleClass()));
            }
            activity.getChildren().clear();
            boolean admin = ctx.can(Permission.MANAGE_USERS);
            List<AuditLog.Entry> entries = ctx.audit().latest(60).stream()
                    .filter(e -> admin || !ACCOUNT_EVENTS.contains(e.action())).limit(10).toList();
            for (AuditLog.Entry e : entries) {
                Label l = new Label(TIME.format(e.at()) + "   " + e.username() + "   "
                        + e.action().name().replace('_', ' ').toLowerCase() + "   " + e.details());
                l.setWrapText(true);
                activity.getChildren().add(l);
            }
            if (entries.isEmpty()) {
                activity.getChildren().add(new Label("Nothing yet."));
            }
        });
    }

    private TableView<Reservation> stayTable(String empty) {
        TableView<Reservation> t = new TableView<>();
        TableColumn<Reservation, String> guest = Ui.column("Guest", Reservation::guestName);
        TableColumn<Reservation, String> room = Ui.column("Room", r -> r.room().number());
        TableColumn<Reservation, String> dates = Ui.column("Dates",
                r -> r.checkIn().format(Ui.DAY) + " - " + r.checkOut().format(Ui.DAY));
        guest.setPrefWidth(160);
        room.setPrefWidth(60);
        dates.setPrefWidth(180);
        t.getColumns().setAll(List.of(guest, room, dates));
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        t.setPlaceholder(new Label(empty));
        t.setPrefHeight(180);
        t.setTooltip(new Tooltip("Double-click a guest to open their folio"));
        t.setOnMouseClicked(e -> {
            Reservation r = t.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && r != null) {
                new FolioWindow(ctx, r.id()).showAndWait();
                refresh();
            }
        });
        return t;
    }
}
