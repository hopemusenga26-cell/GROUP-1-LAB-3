package lodgebilling.view.pages;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lodgebilling.controller.PageContext;
import lodgebilling.model.Bill;
import lodgebilling.model.ChargeCategory;
import lodgebilling.model.FolioEntry;
import lodgebilling.model.FolioSummary;
import lodgebilling.model.Money;
import lodgebilling.model.Payer;
import lodgebilling.model.PaymentMethod;
import lodgebilling.model.Reservation;
import lodgebilling.model.ReservationStatus;
import lodgebilling.model.security.Permission;
import lodgebilling.model.service.LodgeException;
import lodgebilling.model.service.LodgeService;
import lodgebilling.view.FormDialog;
import lodgebilling.view.Styles;
import lodgebilling.view.Ui;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The guest folio: who is staying (left), every line of their bill (right),
 * and a footer that always shows the balance due with the main actions.
 */
public class FolioWindow {

    /** One row of the ledger table: either the worked-out room nights or a posted line. */
    private record Row(LocalDateTime when, String type, String description, String billTo,
                       BigDecimal amount, String by, String note, FolioEntry entry) { }

    private final PageContext ctx;
    private final long reservationId;
    private final Stage stage = new Stage();
    private final TableView<Row> ledger = new TableView<>();
    // Shown only when they have something to say: a company on the bill, or a voided line
    private final TableColumn<Row, String> billTo = Ui.column("Bill to", Row::billTo);
    private final TableColumn<Row, String> note = Ui.column("Note", Row::note);
    private final VBox details = new VBox(6);
    private final VBox totals = new VBox(4);
    private final Label balanceDue = new Label();
    private final HBox actions = new HBox(10);
    private LodgeService.Folio folio;

    public FolioWindow(PageContext ctx, long reservationId) {
        this.ctx = ctx;
        this.reservationId = reservationId;
    }

    public void showAndWait() {
        buildLedger();
        VBox left = Ui.card(null, details);   // the guest's name is the heading
        left.setPrefWidth(270);
        left.setMinWidth(250);
        VBox right = Ui.card(null, ledger, totals);
        VBox.setVgrow(ledger, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox body = new HBox(16, left, right);
        body.setPadding(new Insets(16));

        balanceDue.getStyleClass().add("balance-due");
        balanceDue.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox footer = new HBox(14, balanceDue, Ui.spacer(), actions);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("sticky-footer");

        BorderPane root = new BorderPane(body);
        root.setBottom(footer);
        Scene scene = new Scene(root, 1200, 700);
        scene.getStylesheets().add(Styles.stylesheet());
        Styles.apply(ctx.theme(), root);
        // Keyboard: Ctrl+Shift+C add charge, Ctrl+Shift+P payment, Esc close
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE), stage::close);

        stage.initOwner(ctx.owner());
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setScene(scene);
        reload();
        if (folio == null) {
            return;
        }
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN,
                KeyCombination.SHIFT_DOWN), this::addCharge);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN,
                KeyCombination.SHIFT_DOWN), this::recordPayment);
        stage.showAndWait();
    }

    private void reload() {
        if (!ctx.attempt(() -> folio = ctx.lodge().folio(reservationId))) {
            stage.close();
            return;
        }
        Reservation r = folio.stay();
        FolioSummary s = folio.summary();
        stage.setTitle("Folio " + r.reference() + " - " + r.guestName());

        // Nights and rate are on the room line of the ledger, so they are not repeated here
        details.getChildren().setAll(
                big(r.guestName()),
                Ui.chip(r.status().toString(), r.status().styleClass()),
                info("Booking", r.reference()),
                info("Room", r.room().number() + " (" + r.room().type() + ")"),
                info("Arrival", r.checkIn().format(Ui.DATE)),
                info("Departure", r.checkOut().format(Ui.DATE)));
        if (!r.guestPhone().isBlank()) {
            details.getChildren().add(info("Phone", r.guestPhone()));
        }
        if (r.hasCompany()) {
            details.getChildren().add(info("Company", r.company() + (r.companyPaysRoom() ? " (pays room)" : "")));
        }
        details.getChildren().add(info("Booked by", r.createdBy()));
        if (!r.notes().isBlank()) {
            Label n = new Label("Notes: " + r.notes());
            n.setWrapText(true);
            n.getStyleClass().add("hint");
            details.getChildren().add(n);
        }
        if (ctx.can(Permission.MANAGE_RESERVATIONS)
                && (r.status() == ReservationStatus.BOOKED || r.status() == ReservationStatus.CHECKED_IN)) {
            details.getChildren().add(Ui.button("Edit booking", this::editBooking));
        }

        List<Row> rows = new ArrayList<>();
        Bill room = new Bill("", "", r.room().type(), r.nightlyRate(), r.nights(), BigDecimal.ZERO,
                BigDecimal.ZERO, "", null, "");
        rows.add(new Row(null, "Room", "Room " + r.room().number() + ", " + r.nights() + " night(s) x "
                + Money.format(r.nightlyRate()), r.payerName(r.companyPaysRoom() ? Payer.COMPANY : Payer.GUEST),
                room.getRoomCharge(), "", "", null));
        if (room.getDiscount().signum() > 0) {
            rows.add(new Row(null, "Discount", "Long stay (" + Bill.DISCOUNT_MIN_NIGHTS + "+ nights, "
                    + Bill.discountPercentLabel() + ")", "", room.getDiscount().negate(), "", "", null));
        }
        for (FolioEntry e : folio.entries()) {
            String type = e.isCharge() ? e.categoryLabel() : e.isPayment() ? "Payment: " + e.categoryLabel() : "Refund";
            BigDecimal shown = e.isCharge() ? e.amount() : e.isPayment() ? e.amount().negate() : e.amount();
            rows.add(new Row(e.postedAt(), type, e.description(), r.payerName(e.payer()), shown, e.postedBy(),
                    e.voided() ? "VOID: " + e.voidReason() : "", e));
        }
        ledger.setItems(FXCollections.observableArrayList(rows));
        billTo.setVisible(r.hasCompany());
        note.setVisible(rows.stream().anyMatch(row -> !row.note().isEmpty()));

        totals.getChildren().setAll(
                totalLine("Subtotal", s.subtotal()),
                totalLine("VAT (" + s.vatPercent().stripTrailingZeros().toPlainString() + "%)", s.vat()),
                totalLine("Total", s.total()),
                totalLine("Paid", s.paid()));
        if (s.payers().size() > 1) {
            GridPane split = new GridPane();
            split.setHgap(18);
            split.setVgap(4);
            split.getStyleClass().add("split-grid");
            String[] head = {"Split bill", "Charges", "VAT", "Paid", "Owes"};
            for (int i = 0; i < head.length; i++) {
                Label h = new Label(head[i]);
                h.getStyleClass().add("form-label");
                split.add(h, i, 0);
            }
            int row = 1;
            for (FolioSummary.PayerLine p : s.payers()) {
                split.add(new Label(p.name()), 0, row);
                split.add(new Label(Money.format(p.subtotal())), 1, row);
                split.add(new Label(Money.format(p.vat())), 2, row);
                split.add(new Label(Money.format(p.paid())), 3, row);
                split.add(new Label(Money.format(p.balance())), 4, row);
                row++;
            }
            totals.getChildren().add(split);
        }

        balanceDue.setText((s.balance().signum() > 0 ? "Balance due  " : "Settled  ") + Money.format(s.balance()));
        balanceDue.getStyleClass().removeAll("owing", "settled");
        balanceDue.getStyleClass().add(s.balance().signum() > 0 ? "owing" : "settled");
        buildActions(r);
    }

    private void buildActions(Reservation r) {
        actions.getChildren().clear();
        boolean open = r.status().holdsRoom();
        if (ctx.can(Permission.POST_FOLIO) && open) {
            actions.getChildren().add(Ui.compact("Add charge", this::addCharge));
        }
        if (ctx.can(Permission.POST_FOLIO) && r.status() != ReservationStatus.CANCELLED
                && folio.summary().balance().signum() > 0) {
            actions.getChildren().add(Ui.compact("Record payment", this::recordPayment));
            if (r.hasCompany()) {
                actions.getChildren().add(Ui.compact("Split payment", this::splitPayment));
            }
        }
        if (ctx.can(Permission.VOID_FOLIO_ENTRY)) {
            Button v = Ui.compact("Void line", this::voidLine);
            v.getStyleClass().add("danger");
            actions.getChildren().add(v);
        }
        if (ctx.can(Permission.MANAGE_RESERVATIONS)) {
            if (r.status() == ReservationStatus.BOOKED) {
                actions.getChildren().add(Ui.compact("Check in", () -> {
                    if (ctx.attempt(() -> ctx.lodge().checkIn(ctx.session(), reservationId))) {
                        ctx.success(r.guestName() + " checked in to room " + r.room().number() + ".");
                    }
                    reload();
                }));
            } else if (r.status() == ReservationStatus.CHECKED_IN) {
                Button out = Ui.compact("Check out", () -> {
                    ctx.checkOut(r);
                    reload();
                });
                out.setDefaultButton(true);
                actions.getChildren().add(out);
            }
        }
        actions.getChildren().add(Ui.compact("Close", stage::close));
    }

    // ---------- Actions ----------

    private void editBooking() {
        ReservationDialog.show(ctx, ReservationDialog.Mode.EDIT, folio.stay(), null, null)
                .ifPresent(r -> ctx.success("Booking " + r.reference() + " updated."));
        reload();
    }

    private void addCharge() {
        Reservation r = folio.stay();
        if (!ctx.can(Permission.POST_FOLIO) || !r.status().holdsRoom()) {
            return;
        }
        FormDialog form = new FormDialog(stage, ctx.theme(), "Add charge", "Charge to " + r.reference(),
                "Add charge", "Cancel");
        ComboBox<ChargeCategory> cat = form.add("Category",
                new ComboBox<>(FXCollections.observableArrayList(ChargeCategory.values())));
        cat.setValue(ChargeCategory.BAR);
        TextField desc = form.add("Description", new TextField());
        desc.setPromptText("e.g., 2 x Mosi, dinner");
        TextField amount = form.add("Amount in K", new TextField());
        amount.setPromptText("e.g., 150.50");
        ComboBox<Payer> payer = payerBox(form, r);
        form.addNote("VAT (" + r.vatPercent().stripTrailingZeros().toPlainString()
                + "%) is added on top in the totals.");
        form.onSubmit(() -> ctx.lodge().postCharge(ctx.session(), reservationId, cat.getValue(), desc.getText(),
                parseAmount(amount.getText()), payer.getValue()));
        if (form.showAndWait()) {
            ctx.success("Charge added to " + r.reference() + ".");
        }
        reload();
    }

    private void recordPayment() {
        Reservation r = folio.stay();
        if (!ctx.can(Permission.POST_FOLIO)) {
            return;
        }
        FormDialog form = new FormDialog(stage, ctx.theme(), "Record payment",
                "Payment for " + r.reference() + " - " + Money.format(folio.summary().balance()) + " due",
                "Record payment", "Cancel");
        ComboBox<PaymentMethod> method = form.add("Method",
                new ComboBox<>(FXCollections.observableArrayList(PaymentMethod.values())));
        method.setValue(PaymentMethod.CASH);
        ComboBox<Payer> payer = payerBox(form, r);
        TextField amount = form.add("Amount in K", new TextField());
        amount.setText(owed(payer.getValue()).toPlainString());
        payer.valueProperty().addListener((o, a, b) -> amount.setText(owed(b).toPlainString()));
        TextField ref = form.add("Reference", new TextField());
        ref.setPromptText("Optional: receipt no., mobile money ref");
        // Progressive disclosure: the reference only matters for non-cash payments
        ref.disableProperty().bind(method.valueProperty().isEqualTo(PaymentMethod.CASH));
        form.addNote("Card payments are taken on the lodge's card machine. Never type card numbers here.");
        form.onSubmit(() -> ctx.lodge().recordPayment(ctx.session(), reservationId, method.getValue(),
                parseAmount(amount.getText()), payer.getValue(), ref.getText()));
        if (form.showAndWait()) {
            ctx.success("Payment recorded for " + r.reference() + ".");
        }
        reload();
    }

    private void splitPayment() {
        Reservation r = folio.stay();
        FormDialog form = new FormDialog(stage, ctx.theme(), "Split payment",
                "Guest and company each pay their share", "Record both", "Cancel");
        TextField guestAmt = form.add(r.guestName() + " pays K", new TextField(owed(Payer.GUEST).toPlainString()));
        ComboBox<PaymentMethod> guestMethod = form.add("by",
                new ComboBox<>(FXCollections.observableArrayList(PaymentMethod.values())));
        guestMethod.setValue(PaymentMethod.CASH);
        TextField coAmt = form.add(r.company() + " pays K", new TextField(owed(Payer.COMPANY).toPlainString()));
        ComboBox<PaymentMethod> coMethod = form.add("by ",
                new ComboBox<>(FXCollections.observableArrayList(PaymentMethod.values())));
        coMethod.setValue(PaymentMethod.BANK_TRANSFER);
        TextField ref = form.add("Reference", new TextField());
        form.addNote("Both payments are saved together, or neither is.");
        form.onSubmit(() -> ctx.lodge().recordSplitPayment(ctx.session(), reservationId,
                guestMethod.getValue(), parseOptional(guestAmt.getText()),
                coMethod.getValue(), parseOptional(coAmt.getText()), ref.getText()));
        if (form.showAndWait()) {
            ctx.success("Split payment recorded for " + r.reference() + ".");
        }
        reload();
    }

    private void voidLine() {
        Row row = ledger.getSelectionModel().getSelectedItem();
        if (row == null || row.entry() == null) {
            ctx.error("Select a charge or payment line in the ledger to void.");
            return;
        }
        FolioEntry e = row.entry();
        if (e.voided()) {
            ctx.error("That line is already void.");
            return;
        }
        Ui.askText(stage, ctx.theme(), "Void " + row.type().toLowerCase() + " of " + Money.format(e.amount()) + "?",
                "Reason:").ifPresent(reason -> {
                    if (ctx.attempt(() -> ctx.lodge().voidEntry(ctx.session(), e, reason))) {
                        ctx.success("Line voided.");
                    }
                });
        reload();
    }

    // ---------- Helpers ----------

    private void buildLedger() {
        TableColumn<Row, String> when = Ui.column("When",
                r -> r.when() == null ? "" : r.when().format(Ui.DATE_TIME));
        TableColumn<Row, String> type = Ui.column("Type", Row::type);
        TableColumn<Row, String> desc = Ui.column("Description", Row::description);
        TableColumn<Row, BigDecimal> amt = Ui.moneyColumn("Amount", Row::amount);
        TableColumn<Row, String> by = Ui.column("By", Row::by);
        when.setPrefWidth(125);
        type.setPrefWidth(120);
        desc.setPrefWidth(190);
        billTo.setPrefWidth(120);
        amt.setPrefWidth(105);
        by.setPrefWidth(70);
        note.setPrefWidth(110);
        ledger.getColumns().setAll(List.of(when, type, desc, billTo, amt, by, note));
        ledger.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        ledger.setPlaceholder(new Label("No lines yet."));
        // Voided lines stay visible but struck through, so the history is never lost
        ledger.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(Row item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("voided");
                if (!empty && item != null && item.entry() != null && item.entry().voided()) {
                    getStyleClass().add("voided");
                }
            }
        });
    }

    private ComboBox<Payer> payerBox(FormDialog form, Reservation r) {
        ComboBox<Payer> payer = form.add("Bill to", new ComboBox<>(FXCollections.observableArrayList(
                r.hasCompany() ? List.of(Payer.GUEST, Payer.COMPANY) : List.of(Payer.GUEST))));
        payer.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Payer p) { return p == null ? "" : r.payerName(p); }
            @Override public Payer fromString(String s) { return null; }
        });
        payer.setValue(Payer.GUEST);
        return payer;
    }

    private BigDecimal owed(Payer payer) {
        return folio.summary().payers().stream().filter(p -> p.payer() == payer).findFirst()
                .map(FolioSummary.PayerLine::balance).filter(b -> b.signum() > 0).orElse(BigDecimal.ZERO);
    }

    static BigDecimal parseAmount(String text) throws LodgeException {
        String t = text == null ? "" : text.trim().replace(",", "");
        if (!t.matches("\\d{1,7}(\\.\\d{1,2})?")) {
            throw new LodgeException("Enter an amount like 150 or 150.50.");
        }
        return new BigDecimal(t);
    }

    private static BigDecimal parseOptional(String text) throws LodgeException {
        return text == null || text.isBlank() ? BigDecimal.ZERO : parseAmount(text);
    }

    private static Label big(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("folio-guest");
        l.setWrapText(true);
        return l;
    }

    private static HBox info(String name, String value) {
        Label n = new Label(name);
        n.getStyleClass().add("hint");
        n.setMinWidth(80);
        Label v = new Label(value);
        v.setWrapText(true);
        return new HBox(8, n, v);
    }

    private static HBox totalLine(String name, BigDecimal value) {
        Label n = new Label(name);
        Label v = new Label(Money.format(value));
        v.getStyleClass().add("price-amount");
        HBox h = new HBox(n, Ui.spacer(), v);
        h.setMaxWidth(360);
        return h;
    }

}
