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
import lodgebilling.model.NightAudit;
import lodgebilling.model.Reservation;
import lodgebilling.model.PaymentMethod;
import lodgebilling.model.security.Permission;
import lodgebilling.view.FormDialog;
import lodgebilling.view.Ui;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * End of day: the night audit checklist (no-shows, late departures, rooms not ready),
 * what each member of staff took and how, the cash counted against what the drawer
 * should hold, and closing the day so its payments can no longer change.
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
    private final VBox auditBox = new VBox(8);
    private final Button noShows = Ui.compact("Mark no-shows", this::markNoShows);
    private final VBox auditCard;
    private CashUp shown;
    private NightAudit audit;

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

        auditCard = Ui.card("Night audit", List.of(noShows), auditBox);
        auditCard.managedProperty().bind(auditCard.visibleProperty());

        VBox content = new VBox(14, Ui.row(Ui.fixed("Day"), day), auditCard, metrics,
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
        showAudit();
    }

    /** The checklist only makes sense for today: past days are already history. */
    private void showAudit() {
        boolean today = ctx.lodge().today().equals(day.getValue());
        auditCard.setVisible(today);
        audit = null;
        if (!today) {
            return;
        }
        ctx.attempt(() -> {
            audit = ctx.lodge().nightAudit(ctx.session());
            auditBox.getChildren().clear();
            Label summary = new Label(audit.allClear()
                    ? "All clear: everyone has arrived or left as booked, and the rooms for the next arrivals are ready."
                    : audit.openItems() + " thing(s) to sort out before closing the day.");
            summary.setWrapText(true);
            summary.getStyleClass().setAll("label", "status", audit.allClear() ? "success" : "error");
            auditBox.getChildren().add(summary);
            section("No-shows (should have arrived before today)", audit.overdueArrivals(),
                    r -> r.reference() + "  Room " + r.room().number() + "  " + r.guestName()
                            + ", due " + r.checkIn().format(Ui.DAY));
            section("Still to leave today (check out or extend)", audit.dueOut(),
                    r -> r.reference() + "  Room " + r.room().number() + "  " + r.guestName());
            section("Stayed past check-out (check out or extend)", audit.overstays(),
                    r -> r.reference() + "  Room " + r.room().number() + "  " + r.guestName()
                            + ", was due out " + r.checkOut().format(Ui.DAY));
            if (!audit.roomsNotReady().isEmpty()) {
                auditBox.getChildren().add(heading("Rooms not ready for the next guests (tell housekeeping)"));
                for (var room : audit.roomsNotReady()) {
                    auditBox.getChildren().add(item("Room " + room.number() + " is " + room.status()));
                }
            }
            section("Expected today, not arrived yet (may still come tonight)", audit.arrivingToday(),
                    r -> r.reference() + "  Room " + r.room().number() + "  " + r.guestName());
            int n = audit.overdueArrivals().size();
            noShows.setText(n == 0 ? "Mark no-shows" : "Mark " + n + " no-show" + (n == 1 ? "" : "s"));
            noShows.setDisable(n == 0 || !ctx.can(Permission.MANAGE_RESERVATIONS));
        });
    }

    private void section(String title, List<Reservation> stays,
                         java.util.function.Function<Reservation, String> line) {
        if (stays.isEmpty()) {
            return;
        }
        auditBox.getChildren().add(heading(title));
        for (Reservation r : stays) {
            auditBox.getChildren().add(item(line.apply(r)));
        }
    }

    private static Label heading(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("form-label");
        return l;
    }

    private static Label item(String text) {
        Label l = new Label("•  " + text);
        l.setWrapText(true);
        return l;
    }

    private void markNoShows() {
        if (audit == null || audit.overdueArrivals().isEmpty()) {
            return;
        }
        int n = audit.overdueArrivals().size();
        if (!Ui.confirm(ctx.owner(), ctx.theme(), "Mark " + n + " booking" + (n == 1 ? "" : "s") + " as no-show?",
                "Their rooms become free from today and nothing is charged. A deposit already paid is kept; "
                        + "an admin can void it if the lodge decides to refund it.", "Mark no-shows")) {
            return;
        }
        if (ctx.attempt(() -> ctx.lodge().markOverdueNoShows(ctx.session()))) {
            ctx.success(n + " booking" + (n == 1 ? "" : "s") + " marked as no-show.");
        }
        refresh();
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
        if (audit != null && !audit.allClear() && !Ui.confirm(ctx.owner(), ctx.theme(),
                "The night audit has " + audit.openItems() + " open item(s)",
                "Close the day anyway? You can still sort them out afterwards, but tomorrow's "
                        + "dashboard and room board will be wrong until you do.", "Close anyway")) {
            return;
        }
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
