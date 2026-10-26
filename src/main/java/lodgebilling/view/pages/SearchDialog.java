package lodgebilling.view.pages;

import javafx.collections.FXCollections;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import lodgebilling.controller.PageContext;
import lodgebilling.model.Reservation;
import lodgebilling.view.Ui;

/**
 * Find a stay from anywhere (Ctrl+K): type part of a name, phone, booking number or room,
 * then Enter (or double-click) opens that stay's folio. Down moves from the box to the list.
 */
public final class SearchDialog {

    private SearchDialog() { }

    public static void show(PageContext ctx) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(ctx.owner());
        dialog.setTitle("Search");
        dialog.setHeaderText("Find a guest or booking");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Ui.style(dialog.getDialogPane(), ctx.theme());

        TextField box = new TextField();
        box.setPromptText("Name, phone, booking number or room");
        ListView<Reservation> results = new ListView<>();
        results.setPrefSize(520, 320);
        results.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Reservation r, boolean empty) {
                super.updateItem(r, empty);
                setText(empty || r == null ? null : r.reference() + "   " + r.guestName() + "   room "
                        + r.room().number() + "   " + r.checkIn().format(Ui.DAY) + " - " + r.checkOut().format(Ui.DAY)
                        + "   " + r.status());
            }
        });
        Label empty = new Label("Type to search all stays, past and future.");
        empty.getStyleClass().add("hint");
        results.setPlaceholder(empty);

        box.textProperty().addListener((o, a, text) -> ctx.attempt(() -> {
            results.setItems(FXCollections.observableArrayList(ctx.lodge().search(ctx.session(), text)));
            empty.setText(text.isBlank() ? "Type to search all stays, past and future."
                    : "No stay matches \"" + text.trim() + "\".");
            if (!results.getItems().isEmpty()) {
                results.getSelectionModel().selectFirst();
            }
        }));
        Runnable open = () -> {
            Reservation r = results.getSelectionModel().getSelectedItem();
            if (r != null) {
                dialog.close();
                new FolioWindow(ctx, r.id()).showAndWait();
                ctx.refreshCurrentPage();
            }
        };
        box.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                open.run();
                e.consume();
            } else if (e.getCode() == KeyCode.DOWN && !results.getItems().isEmpty()) {
                results.requestFocus();
                e.consume();
            }
        });
        results.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                open.run();
            }
        });
        results.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                open.run();
            }
        });

        dialog.getDialogPane().setContent(new VBox(10, box, results));
        dialog.setOnShown(e -> box.requestFocus());
        dialog.showAndWait();
    }
}
