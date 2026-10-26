package lodgebilling.view;

import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.KeyCode;
import lodgebilling.model.Bill;
import lodgebilling.model.Money;
import lodgebilling.model.RoomType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** The table of saved receipts, bound directly to the model's list. */
public class BillTableView extends TableView<Bill> {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    public BillTableView(ObservableList<Bill> bills, Runnable onOpen, Runnable onDelete) {
        TableColumn<Bill, String> idCol = new TableColumn<>("Receipt no.");
        idCol.setCellValueFactory(new PropertyValueFactory<>("receiptNumber"));

        TableColumn<Bill, String> guestCol = new TableColumn<>("Guest");
        guestCol.setCellValueFactory(new PropertyValueFactory<>("guestName"));

        TableColumn<Bill, RoomType> roomCol = new TableColumn<>("Room");
        roomCol.setCellValueFactory(new PropertyValueFactory<>("roomType"));

        TableColumn<Bill, Integer> nightsCol = new TableColumn<>("Nights");
        nightsCol.setCellValueFactory(new PropertyValueFactory<>("nights"));

        TableColumn<Bill, LocalDateTime> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(new PropertyValueFactory<>("issuedAt"));
        dateCol.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(LocalDateTime value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : value.format(DATE_FORMAT));
            }
        });

        getColumns().add(idCol);
        getColumns().add(dateCol);
        getColumns().add(guestCol);
        getColumns().add(roomCol);
        getColumns().add(nightsCol);
        getColumns().add(moneyColumn("Total", "total"));
        getColumns().add(moneyColumn("Paid", "paid"));
        getColumns().add(moneyColumn("Balance", "balance"));

        TableColumn<Bill, String> byCol = new TableColumn<>("By");
        byCol.setCellValueFactory(new PropertyValueFactory<>("createdBy"));
        getColumns().add(byCol);

        // Every value sits in the middle of its column, under its heading
        for (TableColumn<Bill, ?> col : getColumns()) {
            col.setStyle("-fx-alignment: CENTER;");
        }

        setItems(bills);
        setPlaceholder(new Label("No receipts saved yet."));
        setFixedCellSize(44);
        setEditable(false);
        getStyleClass().add("bill-table");   // colours live in lodge.css so dark mode works too
        // Columns share the full width in proportion to their preferred widths,
        // so the table fills its card with no empty strip and no scrollbar
        setColumnResizePolicy(CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        idCol.setPrefWidth(90);
        dateCol.setPrefWidth(140);
        guestCol.setPrefWidth(200);
        roomCol.setPrefWidth(110);
        nightsCol.setPrefWidth(70);
        for (int i = 5; i <= 7; i++) {   // the three money columns
            getColumns().get(i).setPrefWidth(110);
        }
        byCol.setPrefWidth(90);
        // Floors so amounts like "K 2812.50" never turn into "K 28..." on a narrow window
        idCol.setMinWidth(100);
        dateCol.setMinWidth(165);
        nightsCol.setMinWidth(55);
        for (int i = 5; i <= 7; i++) {
            getColumns().get(i).setMinWidth(95);
        }
        setMinHeight(160);

        // Double-click a row to open its receipt
        setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && selectedBill() != null) {
                onOpen.run();
            }
        });

        // Enter opens the selected receipt; Delete asks to delete it
        // (Space is left alone: in a table it selects the row)
        setOnKeyPressed(e -> {
            if (selectedBill() == null) {
                return;
            }
            if (e.getCode() == KeyCode.ENTER) {
                onOpen.run();
                e.consume();
            } else if (e.getCode() == KeyCode.DELETE) {
                onDelete.run();
                e.consume();
            }
        });
    }

    /** The highlighted bill, or null when nothing is selected. */
    public Bill selectedBill() {
        return getSelectionModel().getSelectedItem();
    }

    public void clearSelection() {
        getSelectionModel().clearSelection();
    }

    /** A column that shows a BigDecimal as "K 0.00". */
    private static TableColumn<Bill, BigDecimal> moneyColumn(String title, String property) {
        TableColumn<Bill, BigDecimal> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(property));
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : Money.format(value));
            }
        });
        return col;
    }
}
