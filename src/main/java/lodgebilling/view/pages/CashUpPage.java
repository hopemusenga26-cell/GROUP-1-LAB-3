package lodgebilling.view.pages;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import lodgebilling.controller.PageContext;
import lodgebilling.model.CashUp;
import lodgebilling.model.DayClosing;
import lodgebilling.model.Money;
import lodgebilling.model.PaymentMethod;
import lodgebilling.model.security.Permission;
import lodgebilling.view.FormDialog;
import lodgebilling.view.Ui;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * End of day: what each member of staff took and how, the cash counted against what the
 * drawer should hold, and closing the day so its payments can no longer change.
 */
public class CashUpPage implements Page {

    private final PageContext ctx;
    private final DatePicker day = new DatePicker();
    private final HBox metrics = new HBox(14);
    private final TableView<CashUp.Takings> takings = new TableView<>();
    private final Label state = new Label();
    private final Button close = Ui.compact("Close day", this::closeDay);
    private final Button reopen = Ui.compact("Reopen day", this::reopenDay);
    private final ScrollPane root;
    private CashUp shown;

    public CashUpPage(PageContext ctx) {
        this.ctx = ctx;
        day.setValue(ctx.lodge().today());
        day.setPrefWidth(150);
        day.valueProperty().addListener((o, a, b) -> refresh());

        takings.getColumns().setAll(List.of(
                Ui.column("Staff", CashUp.Takings::staff),
                Ui.column("Method", t -> t.method().toString()),
                Ui.moneyColumn("Amount", CashUp.Takings::amount)));
        takings.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        takings.setPlaceholder(new Label("Nothing was taken on this day."));
        takings.setPrefHeight(220);

        state.setWrapText(true);
        reopen.getStyleClass().add("danger");
        reopen.setVisible(ctx.can(Permission.REOPEN_DAY));
        reopen.managedProperty().bind(reopen.visibleProperty());

        Label hint = new Label("Change given back to guests is already taken off. "
                + "Once a day is closed, its payments cannot be added to or voided.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);

        VBox content = new VBox(14, Ui.row(Ui.fixed("Day"), day), metrics,
                Ui.card("Taken by each member of staff", takings),
                Ui.card("Drawer", List.of(close, reopen), state, hint));
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
            shown = ctx.lodge().cashUp(ctx.session(), day.getValue());
            takings.setItems(FXCollections.observableArrayList(shown.takings()));

            Map<PaymentMethod, BigDecimal> byMethod = shown.byMethod();
            metrics.getChildren().setAll(Ui.metric("Total taken", Money.format(shown.total()), null));
            for (PaymentMethod m : PaymentMethod.values()) {
                metrics.getChildren().add(Ui.metric(m.toString(), Money.format(byMethod.get(m)), null));
            }

            DayClosing c = shown.closing();
            state.setText(c == null
                    ? "Open. The drawer should hold " + Money.format(shown.expectedCash()) + " in cash."
                    : "Closed by " + c.closedBy() + " at " + c.closedAt().format(Ui.DATE_TIME) + ". Expected "
                            + Money.format(c.expectedCash()) + ", counted " + Money.format(c.countedCash())
                            + describe(c.difference()) + (c.note().isEmpty() ? "" : " Note: " + c.note()));
            state.getStyleClass().setAll("label", "status");
            if (c != null && c.difference().signum() != 0) {
                state.getStyleClass().add("error");
            }
            close.setDisable(c != null || day.getValue().isAfter(ctx.lodge().today()));
            reopen.setDisable(c == null);
        });
    }

    private static String describe(BigDecimal difference) {
        return switch (difference.signum()) {
            case 0 -> ", exact.";
            case -1 -> ", " + Money.format(difference.negate()) + " short.";
            default -> ", " + Money.format(difference) + " over.";
        };
    }

    private void closeDay() {
        CashUp day = shown;
        FormDialog form = new FormDialog(ctx.owner(), ctx.theme(), "Close day",
                "Count the cash for " + day.date().format(Ui.DATE), "Close day", "Cancel");
        form.addNote("The drawer should hold " + Money.format(day.expectedCash()) + " in cash.");
        TextField counted = form.add("Cash counted", new TextField());
        counted.setPromptText("e.g. 1250.00");
        TextField note = form.add("Note", new TextField());
        note.setPromptText("Needed if the count is short or over");
        form.onSubmit(() -> ctx.lodge().closeDay(ctx.session(), day.date(), Ui.amount(counted.getText()),
                note.getText()));
        if (form.showAndWait()) {
            ctx.success(day.date().format(Ui.DATE) + " closed.");
        }
        refresh();
    }

    private void reopenDay() {
        CashUp day = shown;
        if (!Ui.confirm(ctx.owner(), ctx.theme(), "Reopen " + day.date().format(Ui.DATE) + "?",
                "Its payments can be changed again. Count the drawer and close the day again afterwards.",
                "Reopen day")) {
            return;
        }
        if (ctx.attempt(() -> ctx.lodge().reopenDay(ctx.session(), day.date()))) {
            ctx.success(day.date().format(Ui.DATE) + " reopened.");
        }
        refresh();
    }
}
