package lodgebilling.view;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Control;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import lodgebilling.model.Theme;
import lodgebilling.model.service.UserFacingException;

/**
 * A small pop-up form (label + input rows, an error line, OK and Cancel).
 * Clicking OK runs the submit action; if it throws, the message is shown and
 * the dialog stays open so the user can fix the input.
 */
public class FormDialog {

    /** The work to do when OK is clicked. Throw to keep the dialog open with a message. */
    @FunctionalInterface
    public interface Submit {
        void run() throws UserFacingException;
    }

    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final GridPane grid = new GridPane();
    private final Label error = new Label();
    private final ButtonType okType;
    private int row;
    private Control firstInput;

    /** Gives access to the dialog, e.g. to resize it. */
    public Dialog<ButtonType> dialog() {
        return dialog;
    }

    public FormDialog(Window owner, Theme theme, String title, String header, String okText, String cancelText) {
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(header);

        okType = new ButtonType(okText, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType,
                new ButtonType(cancelText, ButtonBar.ButtonData.CANCEL_CLOSE));

        grid.setHgap(12);
        grid.setVgap(10);
        ColumnConstraints labels = new ColumnConstraints(175);
        ColumnConstraints inputs = new ColumnConstraints(260);
        inputs.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, inputs);

        error.getStyleClass().addAll("status", "error");
        error.setWrapText(true);
        error.setMaxWidth(Double.MAX_VALUE);
        error.managedProperty().bind(error.visibleProperty());
        error.setVisible(false);

        VBox content = new VBox(14, grid, error);
        content.setPadding(new Insets(10, 4, 0, 4));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getStylesheets().add(Styles.stylesheet());
        Styles.apply(theme, dialog.getDialogPane());
        dialog.setOnShown(e -> {
            if (firstInput != null) {
                firstInput.requestFocus();
            }
        });
    }

    /** Adds one labelled input row and returns the input, so callers can keep a reference. */
    public <T extends Control> T add(String labelText, T input) {
        Label label = new Label(labelText);
        label.setLabelFor(input);
        label.getStyleClass().add("form-label");
        input.setMaxWidth(Double.MAX_VALUE);
        grid.add(label, 0, row);
        grid.add(input, 1, row);
        row++;
        if (firstInput == null) {
            firstInput = input;
        }
        return input;
    }

    /** Adds a full-width line of explanatory text. */
    public void addNote(String text) {
        Label note = new Label(text);
        note.setWrapText(true);
        note.getStyleClass().add("hint");
        grid.add(note, 0, row, 2, 1);
        row++;
    }

    public void onSubmit(Submit submit) {
        Button ok = (Button) dialog.getDialogPane().lookupButton(okType);
        ok.addEventFilter(ActionEvent.ACTION, e -> {
            try {
                submit.run();
            } catch (UserFacingException | RuntimeException ex) {
                e.consume();   // stay open
                showError(ex.getMessage());
            }
        });
    }

    public void showError(String message) {
        error.setText(message);
        error.setVisible(message != null && !message.isBlank());
    }

    /** True if the user clicked OK and the submit action succeeded. */
    public boolean showAndWait() {
        return dialog.showAndWait().filter(b -> b == okType).isPresent();
    }
}
