package lodgebilling.view.pages;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import lodgebilling.controller.PageContext;
import lodgebilling.model.DailyStat;
import lodgebilling.model.Money;
import lodgebilling.model.service.LodgeService;
import lodgebilling.controller.CsvWriter;
import lodgebilling.view.Ui;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Admin reports: daily revenue and occupancy (ADR, RevPAR) and who still owes money, with CSV export. */
public class ReportsPage implements Page {

    private static final String REVENUE = "Room revenue is nights sold x booked rate, before discount and VAT.";

    private final PageContext ctx;
    private final DatePicker from = new DatePicker();
    private final DatePicker to = new DatePicker();
    private final HBox metrics = new HBox(14);
    private final TableView<DailyStat> daily = new TableView<>();
    private final TableView<LodgeService.Folio> owing = new TableView<>();
    private final BarChart<String, Number> chart = new BarChart<>(new CategoryAxis(), new NumberAxis());
    private final ScrollPane root;
    private List<DailyStat> stats = List.of();
    private List<LodgeService.Folio> debts = List.of();

    public ReportsPage(PageContext ctx) {
        this.ctx = ctx;
        LocalDate today = ctx.lodge().today();
        from.setValue(today.minusDays(6));
        to.setValue(today);

        from.setPrefWidth(150);
        to.setPrefWidth(150);
        // The report follows the dates as they are picked, so there is no Run button
        from.valueProperty().addListener((o, a, b) -> refresh());
        to.valueProperty().addListener((o, a, b) -> refresh());
        HBox controls = Ui.row(Ui.fixed("From"), from, Ui.fixed("to"), to);

        buildDailyTable();
        buildOwingTable();
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setPrefHeight(240);

        VBox dailyCard = Ui.card("Daily figures", List.of(Ui.compact("Export CSV", this::exportDaily)), daily);
        VBox chartCard = Ui.card("Payments received per day", chart);
        VBox owingCard = Ui.card("Outstanding balances", List.of(Ui.compact("Export CSV", this::exportOwing)), owing);

        VBox content = new VBox(14, controls, metrics, dailyCard, chartCard, owingCard);
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
            stats = ctx.lodge().dailyReport(ctx.session(), from.getValue(), to.getValue());
            debts = ctx.lodge().outstanding(ctx.session());
            daily.setItems(FXCollections.observableArrayList(stats));
            owing.setItems(FXCollections.observableArrayList(debts));

            int sold = stats.stream().mapToInt(DailyStat::roomsSold).sum();
            int available = stats.stream().mapToInt(DailyStat::rooms).sum();
            BigDecimal revenue = stats.stream().map(DailyStat::roomRevenue).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal cash = stats.stream().map(DailyStat::paymentsReceived).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal owed = debts.stream().map(f -> f.summary().balance()).reduce(BigDecimal.ZERO, BigDecimal::add);
            metrics.getChildren().setAll(
                    Ui.metric("Room nights sold", String.valueOf(sold), null),
                    Ui.metric("Occupancy", available == 0 ? "0%" : String.format("%.1f%%", 100.0 * sold / available),
                            null),
                    explained(Ui.metric("ADR", sold == 0 ? "-" : Money.format(
                            revenue.divide(BigDecimal.valueOf(sold), 2, RoundingMode.HALF_UP)), null),
                            "Average daily rate: room revenue / room nights sold.\n" + REVENUE),
                    explained(Ui.metric("RevPAR", available == 0 ? "-" : Money.format(
                            revenue.divide(BigDecimal.valueOf(available), 2, RoundingMode.HALF_UP)), null),
                            "Revenue per available room: room revenue / all rooms.\n" + REVENUE),
                    explained(Ui.metric("Payments received", Money.format(cash), null),
                            "Includes receipts from the Receipts page."),
                    explained(Ui.metric("Owed to the lodge", Money.format(owed),
                            owed.signum() > 0 ? "metric-overdue" : null),
                            "Guests in house or checked out who have not paid in full."));

            XYChart.Series<String, Number> series = new XYChart.Series<>();
            for (DailyStat d : stats) {
                series.getData().add(new XYChart.Data<>(d.date().format(Ui.DAY), d.paymentsReceived()));
            }
            chart.getData().setAll(List.of(series));
        });
    }

    /** How a figure is worked out, shown when the mouse rests on its tile. */
    private static VBox explained(VBox metric, String how) {
        Tooltip tip = new Tooltip(how);
        tip.setShowDelay(Duration.millis(300));
        Tooltip.install(metric, tip);
        return metric;
    }

    private void buildDailyTable() {
        TableColumn<DailyStat, String> date = Ui.column("Date", d -> d.date().format(Ui.DAY));
        TableColumn<DailyStat, String> sold = Ui.column("Sold", d -> d.roomsSold() + " / " + d.rooms());
        TableColumn<DailyStat, String> occ = Ui.column("Occupancy", d -> String.format("%.0f%%", d.occupancyPercent()));
        date.setPrefWidth(110);
        daily.getColumns().setAll(List.of(date, sold, occ,
                Ui.moneyColumn("Room revenue", DailyStat::roomRevenue),
                Ui.moneyColumn("ADR", DailyStat::adr),
                Ui.moneyColumn("RevPAR", DailyStat::revPar),
                Ui.moneyColumn("Payments", DailyStat::paymentsReceived)));
        daily.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        daily.setPrefHeight(260);
    }

    private void buildOwingTable() {
        owing.getColumns().setAll(List.of(
                Ui.column("Ref", f -> f.stay().reference()),
                Ui.column("Guest", f -> f.stay().guestName()),
                Ui.column("Company", f -> f.stay().company()),
                Ui.column("Room", f -> f.stay().room().number()),
                Ui.column("Left", f -> f.stay().checkOut().format(Ui.DAY)),
                Ui.column("Status", f -> f.stay().status().toString()),
                Ui.moneyColumn("Total", f -> f.summary().total()),
                Ui.moneyColumn("Paid", f -> f.summary().paid()),
                Ui.moneyColumn("Owes", f -> f.summary().balance())));
        owing.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        owing.setPlaceholder(new Label("Nobody owes the lodge money."));
        owing.setPrefHeight(200);
        owing.setOnMouseClicked(e -> {
            LodgeService.Folio f = owing.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && f != null) {
                new FolioWindow(ctx, f.stay().id()).showAndWait();
                refresh();
            }
        });
    }

    private void exportDaily() {
        List<List<Object>> rows = new ArrayList<>();
        for (DailyStat d : stats) {
            rows.add(List.of(d.date().toString(), d.rooms(), d.roomsSold(),
                    BigDecimal.valueOf(d.occupancyPercent()).setScale(1, RoundingMode.HALF_UP),
                    d.roomRevenue(), d.adr(), d.revPar(), d.paymentsReceived()));
        }
        export("daily-report-" + from.getValue() + "-to-" + to.getValue() + ".csv",
                List.of("date", "rooms", "rooms_sold", "occupancy_pct", "room_revenue_k", "adr_k", "revpar_k",
                        "payments_k"), rows, "Daily report " + from.getValue() + " to " + to.getValue());
    }

    private void exportOwing() {
        List<List<Object>> rows = new ArrayList<>();
        for (LodgeService.Folio f : debts) {
            rows.add(List.of(f.stay().reference(), f.stay().guestName(), f.stay().company(), f.stay().room().number(),
                    f.stay().checkIn().toString(), f.stay().checkOut().toString(), f.stay().status().name(),
                    f.summary().total(), f.summary().paid(), f.summary().balance()));
        }
        export("outstanding-balances-" + ctx.lodge().today() + ".csv",
                List.of("reference", "guest", "company", "room", "check_in", "check_out", "status", "total_k",
                        "paid_k", "balance_k"), rows, "Outstanding balances");
    }

    private void export(String name, List<String> header, List<List<Object>> rows, String what) {
        if (rows.isEmpty()) {
            ctx.error("Nothing to export.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save CSV");
        chooser.setInitialFileName(name);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        File file = chooser.showSaveDialog(ctx.owner());
        if (file == null) {
            return;
        }
        try {
            CsvWriter.write(file.toPath(), header, rows);
            ctx.lodge().recordExport(ctx.session(), what + " -> " + file.getName());
            ctx.success("Saved " + rows.size() + " rows to " + file.getName() + ".");
        } catch (IOException e) {
            ctx.error("Could not save the file: " + e.getMessage());
        }
    }
}
