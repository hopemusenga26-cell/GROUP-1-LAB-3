package lodgebilling.view;

import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import lodgebilling.model.Theme;
import lodgebilling.security.PasswordHasher;

/**
 * Shows a generated one-time password exactly once, so the admin can pass it on.
 * It is never stored in plain text and cannot be looked up again: if it is lost,
 * the admin simply resets the password once more.
 */
public final class TemporaryPasswordDialog {

    private TemporaryPasswordDialog() { }

    /** Shows the password, then wipes the array it was given. */
    public static void show(Window owner, Theme theme, String header, String username, char[] password) {
        try {
            TextField shown = new TextField(new String(password));
            shown.setEditable(false);   // selectable, but not editable
            shown.getStyleClass().add("temp-password");
            HBox.setHgrow(shown, Priority.ALWAYS);

            Label copied = new Label();
            copied.getStyleClass().add("hint");
            Button copy = new Button("Copy");
            copy.setOnAction(e -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(shown.getText());
                Clipboard.getSystemClipboard().setContent(content);
                copied.setText("Copied. Paste it somewhere safe, then clear your clipboard.");
            });

            Label user = new Label("Username:  " + username);
            user.getStyleClass().add("form-label");
            Label note = new Label("Write this down or copy it now: it will not be shown again. "
                    + "Give it to the person in private (not in a group chat). At their first sign-in "
                    + "they must replace it with a password only they know.");
            note.setWrapText(true);
            note.getStyleClass().add("hint");

            VBox content = new VBox(10, user, new HBox(8, shown, copy), copied, note);
            content.setPadding(new Insets(10, 4, 0, 4));
            content.setPrefWidth(440);

            Alert alert = new Alert(Alert.AlertType.INFORMATION, "", ButtonType.OK);
            alert.initOwner(owner);
            alert.setTitle("One-time password");
            alert.setHeaderText(header);
            alert.getDialogPane().setContent(content);
            Ui.style(alert.getDialogPane(), theme);
            alert.showAndWait();
            shown.clear();
        } finally {
            PasswordHasher.wipe(password);
        }
    }
}
