package lodgebilling.view;

import javafx.collections.FXCollections;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.Window;
import lodgebilling.model.AuditAction;
import lodgebilling.model.AuditEntry;
import lodgebilling.model.Theme;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Admin-only, read-only view of the most recent audit entries. */
public final class AuditLogDialog {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("dd MMM yyyy  HH:mm:ss").withZone(ZoneId.systemDefault());

    private AuditLogDialog() { }

    public static void show(Window owner, Theme theme, List<AuditEntry> entries) {
        TableView<AuditEntry> table = new TableView<>(FXCollections.observableArrayList(entries));

        TableColumn<AuditEntry, Instant> at = new TableColumn<>("Time");
        at.setCellValueFactory(new PropertyValueFactory<>("at"));
        at.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Instant value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : TIME.format(value));
            }
        });
        at.setPrefWidth(200);
        TableColumn<AuditEntry, String> user = new TableColumn<>("User");
        user.setCellValueFactory(new PropertyValueFactory<>("username"));
        user.setPrefWidth(110);
        TableColumn<AuditEntry, AuditAction> action = new TableColumn<>("Action");
        action.setCellValueFactory(new PropertyValueFactory<>("action"));
        action.setPrefWidth(150);
        TableColumn<AuditEntry, String> details = new TableColumn<>("Details");
        details.setCellValueFactory(new PropertyValueFactory<>("details"));
        details.setPrefWidth(290);

        table.getColumns().add(at);
        table.getColumns().add(user);
        table.getColumns().add(action);
        table.getColumns().add(details);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPrefSize(780, 420);
        table.setPlaceholder(new Label("Nothing recorded yet."));

        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Audit log");
        dialog.setHeaderText("Latest " + entries.size() + " entries, newest first");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setContent(table);
        dialog.getDialogPane().getStylesheets().add(Styles.stylesheet());
        Styles.apply(theme, dialog.getDialogPane());
        dialog.showAndWait();
    }
}
