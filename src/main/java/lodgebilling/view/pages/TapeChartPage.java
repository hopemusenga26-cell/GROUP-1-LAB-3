package lodgebilling.view.pages;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import lodgebilling.model.FolioSummary;
import lodgebilling.model.Money;
import lodgebilling.model.Reservation;
import lodgebilling.model.ReservationStatus;
import lodgebilling.model.Room;
import lodgebilling.security.Permission;
import lodgebilling.view.Ui;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * The tape chart: rooms down the side, dates across the top, each stay drawn
 * as a coloured bar. Click a bar to open the folio; click an empty future
 * cell to book that room from that night.
 */
public class TapeChartPage implements Page {

    private static final int DAYS = 14;
    private static final double ROOM_COL = 150;
    private static final double DAY_COL = 74;
    private static final DateTimeFormatter HEAD_DAY = DateTimeFormatter.ofPattern("EEE");
    private static final DateTimeFormatter HEAD_DATE = DateTimeFormatter.ofPattern("dd MMM");

    private final PageContext ctx;
    private final GridPane grid = new GridPane();
    private final Label range = new Label();
    private final BorderPane root = new BorderPane();
    private LocalDate start;

    public TapeChartPage(PageContext ctx) {
        this.ctx = ctx;
        this.start = ctx.lodge().today().minusDays(1);

        range.getStyleClass().add("card-title");
        range.setMinWidth(Region.USE_PREF_SIZE);
        // Only the bar colours need a key: each room already names its own status
        HBox nav = Ui.row(
                Ui.compact("< Week", () -> move(-7)),
                Ui.compact("Today", () -> { start = ctx.lodge().today().minusDays(1); refresh(); }),
                Ui.compact("Week >", () -> move(7)),
                range, Ui.spacer(),
                Ui.chip("Booked", "stay-booked"), Ui.chip("In house", "stay-checked-in"),
                Ui.chip("Left, paid", "stay-checked-out"), Ui.chip("Left, owing", "stay-owing"));
        nav.setPadding(new Insets(14, 20, 10, 20));

        grid.getStyleClass().add("tape");
        ScrollPane scroll = new ScrollPane(grid);
        scroll.getStyleClass().add("main-scroll");
        scroll.setFitToWidth(false);
        BorderPane.setMargin(scroll, new Insets(0, 20, 16, 20));
        root.setTop(nav);
        root.setCenter(scroll);
    }

    @Override
    public Node node() {
        return root;
    }

    private void move(int days) {
        start = start.plusDays(days);
        refresh();
    }

    @Override
    public void refresh() {
        LocalDate end = start.plusDays(DAYS);
        range.setText(start.format(Ui.DATE) + "  to  " + end.minusDays(1).format(Ui.DATE));
        grid.getChildren().clear();
        grid.getColumnConstraints().clear();
        grid.getColumnConstraints().add(new ColumnConstraints(ROOM_COL));
        for (int i = 0; i < DAYS; i++) {
            grid.getColumnConstraints().add(new ColumnConstraints(DAY_COL));
        }

        ctx.attempt(() -> {
            LocalDate today = ctx.lodge().today();
            List<Room> rooms = ctx.lodge().rooms();
            List<Reservation> stays = ctx.lodge().reservationsBetween(start, end);
            Map<Long, FolioSummary> sums = ctx.lodge().summaries(stays);

            // Header row
            Label corner = new Label("Room");
            corner.getStyleClass().add("tape-head");
            corner.setMaxWidth(Double.MAX_VALUE);
            grid.add(corner, 0, 0);
            for (int i = 0; i < DAYS; i++) {
                LocalDate d = start.plusDays(i);
                Label h = new Label(d.format(HEAD_DAY) + "\n" + d.format(HEAD_DATE));
                h.getStyleClass().add("tape-head");
                if (d.equals(today)) h.getStyleClass().add("tape-today");
                h.setMaxWidth(Double.MAX_VALUE);
                h.setAlignment(Pos.CENTER);
                grid.add(h, i + 1, 0);
            }

            for (int row = 0; row < rooms.size(); row++) {
                Room room = rooms.get(row);
                int gridRow = row + 1;
                Label name = new Label(room.number() + "  " + room.type());
                name.getStyleClass().add("tape-room");
                VBox roomCell = new VBox(2, name, Ui.chip(room.status().toString(), room.status().styleClass()));
                roomCell.getStyleClass().add("tape-room-cell");
                grid.add(roomCell, 0, gridRow);

                for (int i = 0; i < DAYS; i++) {
                    LocalDate night = start.plusDays(i);
                    Region cell = new Region();
                    cell.getStyleClass().add("tape-cell");
                    if (night.getDayOfWeek() == DayOfWeek.SATURDAY || night.getDayOfWeek() == DayOfWeek.SUNDAY) {
                        cell.getStyleClass().add("tape-weekend");
                    }
                    if (night.equals(today)) cell.getStyleClass().add("tape-today");
                    if (ctx.can(Permission.MANAGE_RESERVATIONS) && !night.isBefore(today)) {
                        cell.setOnMouseClicked(e -> book(room, night));
                        cell.getStyleClass().add("tape-free");
                        Tooltip tip = new Tooltip("Book room " + room.number() + " from " + night.format(Ui.DAY));
                        tip.setShowDelay(Duration.millis(400));
                        Tooltip.install(cell, tip);
                    }
                    grid.add(cell, i + 1, gridRow);
                }

                for (Reservation r : stays) {
                    if (r.room().id() == room.id()) {
                        grid.add(bar(r, sums.get(r.id())), firstCol(r), gridRow, span(r), 1);
                    }
                }
            }
        });
    }

    private int firstCol(Reservation r) {
        return (int) Math.max(0, ChronoUnit.DAYS.between(start, r.checkIn())) + 1;
    }

    private int span(Reservation r) {
        LocalDate from = r.checkIn().isBefore(start) ? start : r.checkIn();
        LocalDate to = r.checkOut().isAfter(start.plusDays(DAYS)) ? start.plusDays(DAYS) : r.checkOut();
        return (int) Math.max(1, ChronoUnit.DAYS.between(from, to));
    }

    private Node bar(Reservation r, FolioSummary s) {
        // A future booking "owes" its whole price, which is not news; show money only once the guest is in
        boolean owing = s != null && s.balance().signum() > 0 && r.status() != ReservationStatus.BOOKED;
        Label bar = new Label(r.guestName() + (owing ? "  " + Money.format(s.balance()) : ""));
        bar.getStyleClass().addAll("tape-bar", r.status().styleClass());
        if (r.status() == ReservationStatus.CHECKED_OUT && owing) {
            bar.getStyleClass().add("stay-owing");
        }
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setMaxHeight(Double.MAX_VALUE);
        GridPane.setMargin(bar, new Insets(5, 2, 5, 2));
        Tooltip tip = new Tooltip(r.reference() + "  " + r.guestName() + "\n"
                + r.checkIn().format(Ui.DATE) + " to " + r.checkOut().format(Ui.DATE) + " (" + r.nights() + " nights)\n"
                + r.status() + (s == null ? "" : "\nBalance due: " + Money.format(s.balance()))
                + (r.hasCompany() ? "\nCompany: " + r.company() : "")
                + "\nClick to open the folio");
        tip.setShowDelay(Duration.millis(200));
        Tooltip.install(bar, tip);
        bar.setOnMouseClicked(e -> {
            e.consume();
            new FolioWindow(ctx, r.id()).showAndWait();
            refresh();
        });
        return bar;
    }

    private void book(Room room, LocalDate date) {
        ReservationDialog.show(ctx, ReservationDialog.Mode.BOOK, null, room, date)
                .ifPresent(r -> ctx.success("Booked " + r.reference() + ": " + r.guestName() + ", room "
                        + r.room().number() + "."));
        refresh();
    }
}
