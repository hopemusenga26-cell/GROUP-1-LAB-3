package lodgebilling.view.pages;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import lodgebilling.controller.PageContext;
import lodgebilling.model.FolioSummary;
import lodgebilling.model.Reservation;
import lodgebilling.model.ReservationStatus;
import lodgebilling.view.Ui;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** Every booking, filterable and searchable, with the front-desk actions. */
public class StaysPage implements Page {

    private enum Filter {
        CURRENT("Arriving and in house", r -> r.status() == ReservationStatus.BOOKED
                || r.status() == ReservationStatus.CHECKED_IN),
        IN_HOUSE("In house", r -> r.status() == ReservationStatus.CHECKED_IN),
        BOOKED("Booked (future)", r -> r.status() == ReservationStatus.BOOKED),
        CHECKED_OUT("Checked out", r -> r.status() == ReservationStatus.CHECKED_OUT),
        CANCELLED("Cancelled", r -> r.status() == ReservationStatus.CANCELLED),
        ALL("All", r -> true);

        final String label;
        final Predicate<Reservation> test;

        Filter(String label, Predicate<Reservation> test) {
            this.label = label;
            this.test = test;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final PageContext ctx;
    private final TableView<Reservation> table = new TableView<>();
    private final ComboBox<Filter> filter = new ComboBox<>(FXCollections.observableArrayList(Filter.values()));
    private final TextField search = new TextField();
    private final VBox root;
    private Map<Long, FolioSummary> sums = Map.of();

    public StaysPage(PageContext ctx) {
        this.ctx = ctx;
        filter.setValue(Filter.CURRENT);
        filter.valueProperty().addListener((o, a, b) -> refresh());
        search.setPromptText("Search guest, company, room or BK-number");
        search.setPrefWidth(280);
        search.textProperty().addListener((o, a, b) -> refresh());

        // New booking and Walk-in are in the sidebar; these act on the selected stay
        Button open = Ui.compact("Open folio", this::openFolio);
        Button in = Ui.compact("Check in", this::checkIn);
        Button out = Ui.compact("Check out", this::checkOut);
        Button cancel = Ui.compact("Cancel booking", this::cancelBooking);
        cancel.getStyleClass().add("danger");
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, r) -> {
            open.setDisable(r == null);
            in.setDisable(r == null || r.status() != ReservationStatus.BOOKED);
            out.setDisable(r == null || r.status() != ReservationStatus.CHECKED_IN);
            cancel.setDisable(r == null || r.status() != ReservationStatus.BOOKED);
        });
        for (Button b : List.of(open, in, out, cancel)) {
            b.setDisable(true);
        }

        buildTable();
        HBox bar = Ui.row(Ui.fixed("Show"), filter, search, Ui.spacer(), open, in, out, cancel);
        VBox.setVgrow(table, Priority.ALWAYS);
        root = new VBox(12, bar, table);
        root.setPadding(new Insets(16, 20, 16, 20));
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void refresh() {
        ctx.attempt(() -> {
            String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
            List<Reservation> rows = ctx.lodge().reservations().stream()
                    .filter(filter.getValue().test)
                    .filter(r -> q.isEmpty() || (r.guestName() + " " + r.company() + " " + r.room().number()
                            + " " + r.reference()).toLowerCase(Locale.ROOT).contains(q))
                    .toList();
            sums = ctx.lodge().summaries(rows);
            table.setItems(FXCollections.observableArrayList(rows));
        });
    }

    private void buildTable() {
        TableColumn<Reservation, String> ref = Ui.column("Ref", Reservation::reference);
        TableColumn<Reservation, String> guest = Ui.column("Guest", r -> r.guestName()
                + (r.hasCompany() ? "  (" + r.company() + ")" : ""));
        TableColumn<Reservation, String> room = Ui.column("Room", r -> r.room().number());
        TableColumn<Reservation, String> in = Ui.column("Arrive", r -> r.checkIn().format(Ui.DAY));
        TableColumn<Reservation, String> out = Ui.column("Leave", r -> r.checkOut().format(Ui.DAY));
        TableColumn<Reservation, String> nights = Ui.column("Nights", r -> String.valueOf(r.nights()));
        TableColumn<Reservation, ReservationStatus> status = new TableColumn<>("Status");
        status.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue().status()));
        status.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(ReservationStatus s, boolean empty) {
                super.updateItem(s, empty);
                setText(null);
                setGraphic(empty || s == null ? null : Ui.chip(s.toString(), s.styleClass()));
            }
        });
        TableColumn<Reservation, BigDecimal> balance = Ui.moneyColumn("Balance",
                r -> sums.containsKey(r.id()) ? sums.get(r.id()).balance() : null);
        ref.setPrefWidth(70);
        guest.setPrefWidth(240);
        room.setPrefWidth(60);
        in.setPrefWidth(100);
        out.setPrefWidth(100);
        nights.setPrefWidth(60);
        status.setPrefWidth(120);
        balance.setPrefWidth(110);
        table.getColumns().setAll(List.of(ref, guest, room, in, out, nights, status, balance));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("No stays match."));
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) openFolio();
        });
        table.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) openFolio();
        });
    }

    private Reservation selected() {
        Reservation r = table.getSelectionModel().getSelectedItem();
        if (r == null) {
            ctx.error("Select a stay in the table first.");
        }
        return r;
    }

    private void openFolio() {
        Reservation r = selected();
        if (r != null) {
            new FolioWindow(ctx, r.id()).showAndWait();
            refresh();
        }
    }

    private void checkIn() {
        Reservation r = selected();
        if (r != null && ctx.attempt(() -> ctx.lodge().checkIn(ctx.session(), r.id()))) {
            ctx.success(r.guestName() + " checked in to room " + r.room().number() + ".");
        }
        refresh();
    }

    private void checkOut() {
        Reservation r = selected();
        if (r != null) {
            ctx.checkOut(r);
            refresh();
        }
    }

    private void cancelBooking() {
        Reservation r = selected();
        if (r == null) {
            return;
        }
        if (Ui.confirm(ctx.owner(), ctx.theme(), "Cancel booking " + r.reference() + "?",
                r.guestName() + ", room " + r.room().number() + ", " + r.checkIn().format(Ui.DATE)
                        + ". The room becomes free for those nights.", "Cancel booking")
                && ctx.attempt(() -> ctx.lodge().cancel(ctx.session(), r.id()))) {
            ctx.success("Booking " + r.reference() + " cancelled.");
        }
        refresh();
    }
}
