package lodgebilling.view;

import lodgebilling.controller.BillingActions;

import javafx.beans.property.ObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import lodgebilling.model.RoomType;
import lodgebilling.model.BillFormData;
import lodgebilling.model.BillValidator;
import lodgebilling.model.FormField;
import lodgebilling.model.StatusMessage;

/** The "New receipt" card: the input fields plus the Calculate and Save buttons. */
public class BillFormView extends VBox {

    private static final String ROOM_PROMPT = "Choose a room type";
    // Matches ":error" in the stylesheet: the red outline on the input that needs fixing
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    private final TextField nameField = new TextField();
    private final ComboBox<RoomType> roomBox = new ComboBox<>();
    private final TextField nightsField = new TextField();
    private final TextField extrasField = new TextField();
    private final TextField paidField = new TextField();
    private final TextArea notesArea = new TextArea();
    private final Button calculateButton = new Button("_Calculate");
    private final Button saveButton = new Button("_Save receipt");
    private final Button newGuestButton = new Button("New guest");

    public BillFormView(BillingActions actions, ObjectProperty<StatusMessage> status) {
        super(12);
        Label title = new Label("New receipt");
        title.getStyleClass().add("card-title");

        configureFields();
        calculateButton.setOnAction(e -> actions.calculate());
        saveButton.setDefaultButton(true);         // Enter triggers Save receipt
        saveButton.setOnAction(e -> actions.saveBill());
        newGuestButton.setOnAction(e -> actions.newGuest());   // clears the form

        // Tell the controller about every edit, so it can drop an error the user is fixing
        for (FormField field : FormField.values()) {
            valueOf(field).addListener((obs, old, now) -> actions.formEdited(field));
        }
        // Outline the input an error message is about, until the error goes away
        status.addListener((obs, old, message) -> showError(message));

        getChildren().addAll(title, buildGrid());
        getStyleClass().add("card");
    }

    // ---------- Public API used by the controller ----------

    /** The raw text and selection exactly as the user entered them. */
    public BillFormData read() {
        return new BillFormData(nameField.getText(), roomBox.getValue(), nightsField.getText(),
                extrasField.getText(), paidField.getText(), notesArea.getText());
    }

    public void clear() {
        nameField.clear();
        roomBox.setValue(null);
        nightsField.clear();
        extrasField.clear();
        paidField.clear();
        notesArea.clear();
    }

    public void focus(FormField field) {
        control(field).requestFocus();
    }

    // ---------- Fields ----------

    private Control control(FormField field) {
        return switch (field) {
            case NAME -> nameField;
            case ROOM -> roomBox;
            case NIGHTS -> nightsField;
            case EXTRAS -> extrasField;
            case PAID -> paidField;
            case NOTES -> notesArea;
        };
    }

    /** The text or selection of a field, as something that can be watched for changes. */
    private ObservableValue<?> valueOf(FormField field) {
        return switch (field) {
            case NAME -> nameField.textProperty();
            case ROOM -> roomBox.valueProperty();
            case NIGHTS -> nightsField.textProperty();
            case EXTRAS -> extrasField.textProperty();
            case PAID -> paidField.textProperty();
            case NOTES -> notesArea.textProperty();
        };
    }

    private void showError(StatusMessage message) {
        for (FormField field : FormField.values()) {
            control(field).pseudoClassStateChanged(ERROR, message.isError() && message.field() == field);
        }
    }

    // ---------- Building the form ----------

    private void configureFields() {
        nameField.setPromptText("e.g., John Phiri");

        roomBox.getItems().addAll(RoomType.values());
        roomBox.setPromptText(ROOM_PROMPT);
        roomBox.setMaxWidth(Double.MAX_VALUE);
        // Keep the hint visible again after the box is reset to "nothing selected"
        roomBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(RoomType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? ROOM_PROMPT : item.toString());
            }
        });

        nightsField.setPromptText("1 to " + BillValidator.MAX_NIGHTS);
        extrasField.setPromptText("0");
        paidField.setPromptText("0");

        notesArea.setPromptText("Optional");
        notesArea.setPrefRowCount(2);
        notesArea.setWrapText(true);
        // Tab normally types a tab inside a TextArea; make it move focus instead
        // so keyboard users are not trapped in the notes box
        notesArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.TAB && !e.isControlDown()) {
                e.consume();
                (e.isShiftDown() ? paidField : calculateButton).requestFocus();
            }
        });
    }

    /**
     * Short fields share a row (room + nights, extras + paid) so the whole form
     * and the saved-bills table fit on a laptop screen without scrolling.
     */
    private GridPane buildGrid() {
        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(10);
        addRow(form, 0, 0, 3, "Guest _name", nameField);
        addRow(form, 1, 0, 1, "_Room type", roomBox);
        addRow(form, 1, 2, 1, "Nigh_ts", nightsField);
        addRow(form, 2, 0, 1, "_Extras in K", extrasField);
        addRow(form, 2, 2, 1, "_Amount paid in K", paidField);
        addRow(form, 3, 0, 3, "N_otes", notesArea);
        HBox buttons = new HBox(10, calculateButton, saveButton, Ui.spacer(), newGuestButton);
        buttons.setMaxWidth(Double.MAX_VALUE);
        form.add(buttons, 1, 4, 3, 1);

        ColumnConstraints labelCol = new ColumnConstraints(90);
        ColumnConstraints inputCol = new ColumnConstraints();
        inputCol.setHgrow(Priority.ALWAYS);
        ColumnConstraints secondLabelCol = new ColumnConstraints(140);
        ColumnConstraints secondInputCol = new ColumnConstraints();
        secondInputCol.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labelCol, inputCol, secondLabelCol, secondInputCol);
        return form;
    }

    /**
     * Adds a label + control pair starting at the given column; the control spans
     * inputSpan columns. The label's mnemonic (Alt+letter) focuses the control.
     */
    private static void addRow(GridPane form, int row, int col, int inputSpan,
                               String labelText, Control control) {
        Label label = new Label(labelText);
        label.setMnemonicParsing(true);
        label.setLabelFor(control);
        label.getStyleClass().add("form-label");
        GridPane.setValignment(label, VPos.TOP);
        GridPane.setMargin(label, new Insets(7, 0, 0, 0));
        form.add(label, col, row);
        form.add(control, col + 1, row, inputSpan, 1);
    }
}
