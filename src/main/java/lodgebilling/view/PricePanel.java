package lodgebilling.view;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import lodgebilling.model.Bill;
import lodgebilling.model.Money;
import lodgebilling.model.RateCard;
import lodgebilling.model.RoomType;

/** Card listing the room prices and the billing rules. */
public class PricePanel extends VBox {

    private final RateCard rates;
    private final VBox priceRows = new VBox(6);

    public PricePanel(RateCard rates) {
        super(6);
        this.rates = rates;
        Label heading = new Label("Room prices");
        heading.getStyleClass().add("card-title");
        getChildren().addAll(heading, priceRows);
        refresh();

        Label rules = new Label(
                Bill.DISCOUNT_MIN_NIGHTS + "+ nights: " + Bill.discountPercentLabel()
                + " off the room charge (not extras). No VAT on receipts.");
        rules.setWrapText(true);
        rules.getStyleClass().add("hint");
        VBox.setMargin(rules, new Insets(10, 0, 0, 0));
        getChildren().add(rules);

        getStyleClass().add("card");
        setPrefWidth(280);
        setMinWidth(240);
    }

    /** Re-reads the prices (an admin may have changed them). */
    public void refresh() {
        priceRows.getChildren().clear();
        for (RoomType room : RoomType.values()) {
            priceRows.getChildren().add(priceRow(room));
        }
    }

    private HBox priceRow(RoomType room) {
        Label name = new Label(room.getLabel());
        name.getStyleClass().add("price-row");
        Label amount = new Label(Money.format(rates.rateFor(room)) + " / night");
        amount.getStyleClass().add("price-amount");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return new HBox(name, spacer, amount);
    }
}
