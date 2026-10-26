package lodgebilling.view;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import lodgebilling.model.Money;
import lodgebilling.model.Theme;
import lodgebilling.model.service.LodgeException;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Small building blocks shared by the pages, so every screen looks and behaves the same. */
public final class Ui {

    public static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE dd MMM");
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM HH:mm");

    private Ui() { }

    /** A section ruled off from what is above it, with a heading (or none when the title is null). */
    public static VBox card(String title, Node... content) {
        return card(title, List.of(), content);
    }

    /** A panel whose heading has buttons on the right, for actions on what the panel shows. */
    public static VBox card(String title, List<? extends Node> actions, Node... content) {
        VBox box = new VBox(10);
        if (title != null) {
            Label heading = new Label(title);
            heading.getStyleClass().add("card-title");
            HBox top = row(heading, spacer());
            top.getChildren().addAll(actions);
            box.getChildren().add(top);
        }
        box.getChildren().addAll(content);
        box.getStyleClass().add("card");
        return box;
    }

    /** A number with what it counts, for dashboards. "metric-overdue" colours the number red. */
    public static VBox metric(String caption, String value, String styleClass) {
        Label v = new Label(value);
        v.getStyleClass().add("metric-value");
        Label c = new Label(caption);
        c.getStyleClass().add("metric-caption");
        VBox box = new VBox(4, v, c);
        box.getStyleClass().addAll("card", "metric");
        if (styleClass != null) {
            box.getStyleClass().add(styleClass);
        }
        HBox.setHgrow(box, Priority.ALWAYS);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    /** A small coloured pill, e.g. a room or booking status. */
    public static Label chip(String text, String styleClass) {
        Label l = new Label(text);
        l.getStyleClass().addAll("chip", styleClass);
        return l;
    }

    public static Button button(String text, Runnable action) {
        Button b = new Button(text);
        b.setOnAction(e -> action.run());
        return b;
    }

    /** A button that is only as wide as its text, for crowded toolbars. */
    public static Button compact(String text, Runnable action) {
        Button b = button(text, action);
        b.getStyleClass().add("compact");
        b.setMinWidth(Region.USE_PREF_SIZE);
        return b;
    }

    /** A label that never shrinks into "...". */
    public static Label fixed(String text) {
        Label l = new Label(text);
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    public static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    public static HBox row(Node... nodes) {
        HBox h = new HBox(10, nodes);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** A right-aligned money column, formatted "K 0.00". */
    public static <S> TableColumn<S, BigDecimal> moneyColumn(String title, Function<S, BigDecimal> value) {
        TableColumn<S, BigDecimal> col = new TableColumn<>(title);
        col.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(value.apply(c.getValue())));
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : Money.format(v));
            }
        });
        col.setStyle("-fx-alignment: CENTER-RIGHT;");
        return col;
    }

    /** A text column computed from the row object. */
    public static <S> TableColumn<S, String> column(String title, Function<S, String> value) {
        TableColumn<S, String> col = new TableColumn<>(title);
        col.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(value.apply(c.getValue())));
        return col;
    }

    public static void style(DialogPane pane, Theme theme) {
        pane.getStylesheets().add(Styles.stylesheet());
        Styles.apply(theme, pane);
    }

    public static boolean confirm(Window owner, Theme theme, String header, String text, String okText) {
        ButtonType ok = new ButtonType(okText, javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, text, ok, ButtonType.CANCEL);
        a.initOwner(owner);
        a.setHeaderText(header);
        style(a.getDialogPane(), theme);
        // Cancel is the safe default for anything irreversible
        ((Button) a.getDialogPane().lookupButton(ok)).setDefaultButton(false);
        ((Button) a.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        return a.showAndWait().filter(b -> b == ok).isPresent();
    }

    /** A question where yes is the usual answer, so it is the default (Enter). */
    public static boolean ask(Window owner, Theme theme, String header, String text, String yesText, String noText) {
        ButtonType yes = new ButtonType(yesText, javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType no = new ButtonType(noText, javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, text, yes, no);
        a.initOwner(owner);
        a.setHeaderText(header);
        style(a.getDialogPane(), theme);
        return a.showAndWait().filter(b -> b == yes).isPresent();
    }

    /** An amount typed into a form, e.g. "150" or "150.50"; refused with a message otherwise. */
    public static BigDecimal amount(String text) throws LodgeException {
        return Money.parse(text).orElseThrow(() -> new LodgeException("Enter an amount like 150 or 150.50."));
    }

    public static void info(Window owner, Theme theme, String header, String text) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, text);
        a.initOwner(owner);
        a.setHeaderText(header);
        style(a.getDialogPane(), theme);
        a.showAndWait();
    }

    public static Optional<String> askText(Window owner, Theme theme, String header, String prompt) {
        TextInputDialog d = new TextInputDialog();
        d.initOwner(owner);
        d.setHeaderText(header);
        d.setContentText(prompt);
        d.setGraphic(null);
        style(d.getDialogPane(), theme);
        return d.showAndWait();
    }

    public static TextArea notesArea() {
        TextArea t = new TextArea();
        t.setPrefRowCount(2);
        t.setWrapText(true);
        return t;
    }
}
