package lodgebilling.view;

import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import lodgebilling.controller.BillingActions;
import lodgebilling.model.BillingModel;
import lodgebilling.security.Permission;

import java.util.List;

/**
 * The buttons in the heading of the saved receipts table. They act on the selected
 * row; Delete appears only for roles that may delete.
 */
public class AppToolBar extends HBox {

    private final Button receiptButton;
    private final Button printButton;
    private final Button deleteButton;

    public AppToolBar(BillingActions actions, BillingModel model) {
        super(8);
        receiptButton = Ui.compact("View receipt", actions::viewReceipt);
        printButton = Ui.compact("Print receipt", actions::printReceipt);
        deleteButton = Ui.compact("Delete receipt", actions::deleteSelected);
        deleteButton.getStyleClass().add("danger");

        setAlignment(Pos.CENTER_RIGHT);
        getChildren().addAll(receiptButton, printButton);
        if (model.session().has(Permission.DELETE_BILL)) {
            getChildren().add(deleteButton);
        }
    }

    /** Greys the buttons out while there is no row to act on. */
    public void disableWhen(ObservableBooleanValue nothingSelected) {
        for (Button b : List.of(receiptButton, printButton, deleteButton)) {
            b.disableProperty().bind(nothingSelected);
        }
    }

    /** True for the buttons that act on the selected table row. */
    public boolean actsOnSelection(Node node) {
        return node == receiptButton || node == printButton || node == deleteButton;
    }
}
