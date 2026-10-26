package lodgebilling.view;

import javafx.collections.FXCollections;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import lodgebilling.model.Money;
import lodgebilling.model.Payer;
import lodgebilling.model.PaymentLine;
import lodgebilling.model.PaymentMethod;
import lodgebilling.model.Theme;
import lodgebilling.model.service.UserFacingException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The one window for taking money, used by the folio, the receipts list, the Stays page,
 * the dashboard and check-out. Each amount starts at what that payer owes, so a guest
 * paying in full is Enter; lowering the amount takes part of it.
 */
public final class PaymentDialog {

    /** What one payer (guest or company) still owes. */
    public record Due(Payer payer, String name, BigDecimal owed) { }

    /** Records the payments; throwing keeps the window open with the message. */
    @FunctionalInterface
    public interface Handler {
        void pay(List<PaymentLine> lines) throws UserFacingException;
    }

    private record Line(Due due, TextField amount, ComboBox<PaymentMethod> method) { }

    private PaymentDialog() { }

    /** @return true once the handler has recorded the payment */
    public static boolean show(Window owner, Theme theme, String title, String header, String okText,
                               List<Due> dues, Handler handler) {
        FormDialog form = new FormDialog(owner, theme, title, header, okText, "Cancel");
        List<Line> lines = new ArrayList<>();
        for (Due due : dues) {
            String who = dues.size() > 1 ? due.name() + " pays (K)" : "Amount (K)";
            TextField amount = form.add(who, new TextField(due.owed().toPlainString()));
            ComboBox<PaymentMethod> method = form.add("Method",
                    new ComboBox<>(FXCollections.observableArrayList(PaymentMethod.values())));
            method.setValue(due.payer() == Payer.COMPANY ? PaymentMethod.BANK_TRANSFER : PaymentMethod.CASH);
            lines.add(new Line(due, amount, method));
        }
        TextField reference = form.add("Reference", new TextField());
        reference.setPromptText("Optional: mobile money or bank ref");
        TextField cash = form.add("Cash received (K)", new TextField());
        cash.setPromptText("Optional: works out the change");
        Label change = form.add("Change", new Label("-"));
        change.getStyleClass().add("price-amount");
        form.addNote("Card payments are taken on the lodge's card machine. Never type card numbers here.");

        // Only what is needed for the chosen methods can be filled in
        Runnable update = () -> {
            boolean anyCash = lines.stream().anyMatch(l -> l.method().getValue() == PaymentMethod.CASH);
            boolean allCash = lines.stream().allMatch(l -> l.method().getValue() == PaymentMethod.CASH);
            reference.setDisable(allCash);
            cash.setDisable(!anyCash);
            change.setText(change(lines, cash.getText()));
        };
        for (Line l : lines) {
            l.amount().textProperty().addListener((o, a, b) -> update.run());
            l.method().valueProperty().addListener((o, a, b) -> update.run());
        }
        cash.textProperty().addListener((o, a, b) -> update.run());
        update.run();

        form.onSubmit(() -> {
            List<PaymentLine> payments = new ArrayList<>();
            for (Line l : lines) {
                if (!l.amount().getText().isBlank()) {
                    payments.add(new PaymentLine(l.due().payer(), l.method().getValue(),
                            Ui.amount(l.amount().getText()), reference.isDisabled() ? "" : reference.getText()));
                }
            }
            handler.pay(payments);
        });
        // The amount is selected, so typing a smaller figure replaces it
        if (!lines.isEmpty()) {
            TextField first = lines.get(0).amount();
            form.dialog().setOnShown(e -> {
                first.requestFocus();
                first.selectAll();
            });
        }
        return form.showAndWait();
    }

    /** Cash handed over minus the cash amounts being paid, e.g. "K 50.00". */
    private static String change(List<Line> lines, String cashText) {
        if (cashText == null || cashText.isBlank()) {
            return "-";
        }
        BigDecimal given = Money.parse(cashText).orElse(null);
        BigDecimal due = BigDecimal.ZERO;
        for (Line l : lines) {
            if (l.method().getValue() == PaymentMethod.CASH) {
                BigDecimal a = Money.parse(l.amount().getText()).orElse(null);
                if (a == null) {
                    return "-";
                }
                due = due.add(a);
            }
        }
        if (given == null) {
            return "-";
        }
        BigDecimal back = given.subtract(due);
        return back.signum() < 0 ? "Not enough (short " + Money.format(back.negate()) + ")" : Money.format(back);
    }
}
