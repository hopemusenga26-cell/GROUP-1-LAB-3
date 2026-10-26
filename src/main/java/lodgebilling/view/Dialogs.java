package lodgebilling.view;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import lodgebilling.controller.BillingActions;
import lodgebilling.model.Bill;
import lodgebilling.model.BillingModel;
import lodgebilling.model.Money;

import java.util.Optional;

/** The pop-up windows of the receipts list (receipt, delete confirmation, password), owned by the main window. */
public class Dialogs {

    private final Stage owner;
    private final BillingModel model;
    private final BillingActions actions;

    public Dialogs(Stage owner, BillingModel model, BillingActions actions) {
        this.owner = owner;
        this.model = model;
        this.actions = actions;
    }

    public void showReceipt(Bill bill) {
        StackPane paper = new StackPane(new ReceiptView(bill));
        paper.setPadding(new Insets(10, 20, 20, 20));
        ScrollPane scroll = new ScrollPane(paper);
        scroll.getStyleClass().add("receipt-scroll");
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(400);
        scroll.setPrefViewportHeight(600);

        ButtonType print = new ButtonType("Print receipt", ButtonBar.ButtonData.LEFT);
        Alert dialog = new Alert(Alert.AlertType.INFORMATION, "", print, ButtonType.CLOSE);
        dialog.setTitle("Receipt " + bill.getReceiptNumber());
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.getDialogPane().setContent(scroll);

        // Keep the dialog open after printing so the receipt can be checked or printed again.
        // Printing goes through the controller, like the toolbar button, so the status bar
        // reports the result
        Button printButton = (Button) dialog.getDialogPane().lookupButton(print);
        printButton.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            actions.printReceiptFor(bill);
        });
        show(dialog);
    }

    /** Sends the receipt to a printer the user picks. Returns false if cancelled or it failed. */
    public boolean printReceipt(Bill bill) {
        return ReceiptView.print(owner, model.themeProperty().get(), bill);
    }

    /** Asks the user to confirm; true only if they chose Delete. */
    public boolean confirmDelete(Bill bill) {
        ButtonType delete = new ButtonType("Delete");
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete receipt " + bill.getReceiptNumber() + " for " + bill.getGuestName()
                        + " (" + Money.format(bill.getTotal()) + ")?",
                delete, ButtonType.CANCEL);
        confirm.setTitle("Confirm deletion");
        confirm.setHeaderText("Delete the selected receipt?");

        // Make Cancel the safe default: Enter or a stray key press keeps the bill
        Button deleteButton = (Button) confirm.getDialogPane().lookupButton(delete);
        Button cancelButton = (Button) confirm.getDialogPane().lookupButton(ButtonType.CANCEL);
        deleteButton.setDefaultButton(false);
        deleteButton.getStyleClass().add("danger");
        cancelButton.setDefaultButton(true);
        confirm.setOnShown(e -> cancelButton.requestFocus());

        return show(confirm).orElse(ButtonType.CANCEL) == delete;
    }

    /** @return true if the password was changed */
    public boolean changePassword(boolean forced, ChangePasswordDialog.Change change) {
        return ChangePasswordDialog.show(owner, model.themeProperty().get(), forced, change);
    }

    /** Dialogs are separate windows, so they need the stylesheet and current theme too. */
    private Optional<ButtonType> show(Alert dialog) {
        dialog.initOwner(owner);
        dialog.getDialogPane().getStylesheets().add(Styles.stylesheet());
        Styles.apply(model.themeProperty().get(), dialog.getDialogPane());
        return dialog.showAndWait();
    }
}
