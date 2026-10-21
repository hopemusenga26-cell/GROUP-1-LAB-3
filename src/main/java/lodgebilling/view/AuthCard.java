package lodgebilling.view;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import lodgebilling.model.Theme;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Shared look for the sign-in and first-run screens: the lodge's name and today's
 * date on an ink panel on the left, and the form on paper on the right (a column
 * of labelled inputs, an error line and one main button). The light/dark switch
 * sits in the top-right corner. On a narrow window only the form is shown.
 */
public abstract class AuthCard extends StackPane {

    private static final Duration FADE = Duration.millis(180);
    private static final double SHOW_BRAND_FROM = 760;
    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy");

    private final VBox card = new VBox();
    private final VBox brand = new VBox(6);
    private final HBox layout;
    private final Label error = new Label();
    private final ThemeToggle themeToggle;
    private final ObjectProperty<Theme> theme;
    private final ChangeListener<Theme> themeListener = (obs, old, now) -> Styles.apply(now, this);
    private TextField firstInput;

    /** The heading and the line under it are optional (null leaves them out). */
    AuthCard(String heading, String subheading, ObjectProperty<Theme> theme) {
        this.theme = theme;

        Label wordmark = new Label("Lodge Billing");
        wordmark.getStyleClass().add("auth-wordmark");
        Label date = new Label(LocalDate.now().format(TODAY));
        date.getStyleClass().add("auth-date");
        brand.getChildren().addAll(wordmark, date);
        brand.getStyleClass().add("auth-brand");
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setMinWidth(300);
        brand.prefWidthProperty().bind(widthProperty().multiply(0.42));
        brand.managedProperty().bind(brand.visibleProperty());

        if (heading != null) {
            Label head = new Label(heading);
            head.getStyleClass().add("card-title");
            head.setWrapText(true);
            card.getChildren().add(head);
        }
        if (subheading != null) {
            Label sub = new Label(subheading);
            sub.getStyleClass().add("hint");
            sub.setWrapText(true);
            card.getChildren().add(sub);
        }

        error.getStyleClass().addAll("status", "error");
        error.setWrapText(true);
        error.setMaxWidth(Double.MAX_VALUE);
        error.managedProperty().bind(error.visibleProperty());
        error.setVisible(false);

        card.getStyleClass().addAll("card", "auth-card");
        card.setMaxWidth(340);
        card.setMaxHeight(USE_PREF_SIZE);

        themeToggle = new ThemeToggle(theme, () -> theme.set(theme.get().toggled()));
        themeToggle.setFocusTraversable(false);
        StackPane.setAlignment(themeToggle, Pos.TOP_RIGHT);

        StackPane formSide = new StackPane(card, themeToggle);
        formSide.getStyleClass().add("auth-form-side");
        HBox.setHgrow(formSide, Priority.ALWAYS);

        layout = new HBox(brand, formSide);
        layout.setFillHeight(true);
        // A narrow window shows only the form
        widthProperty().addListener((obs, old, w) -> brand.setVisible(w.doubleValue() >= SHOW_BRAND_FROM));

        Styles.apply(theme.get(), this);
        theme.addListener(themeListener);
        getChildren().add(layout);
    }

    protected <T extends TextField> T field(String labelText, T input) {
        Label label = new Label(labelText);
        label.setLabelFor(input);
        label.getStyleClass().add("form-label");
        card.getChildren().addAll(label, input);
        if (firstInput == null) {
            firstInput = input;
        }
        return input;
    }

    protected void finish(Button main) {
        main.setDefaultButton(true);   // Enter submits
        main.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(error, new Insets(10, 0, 0, 0));
        VBox.setMargin(main, new Insets(14, 0, 0, 0));
        card.getChildren().addAll(error, main);
    }

    /** A line of small print under the main button. */
    protected void footnote(String text) {
        Label note = new Label(text);
        note.getStyleClass().add("hint");
        note.setWrapText(true);
        note.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(note, new Insets(6, 0, 0, 0));
        card.getChildren().add(note);
    }

    public void showError(String message) {
        error.setText(message);
        error.setVisible(true);
    }

    protected void clearError() {
        error.setVisible(false);
    }

    /** Call once the screen is showing: the form fades in and the first input gets the cursor. */
    public void install() {
        Motion.enter(card);
        Platform.runLater(() -> {
            if (firstInput != null) {
                firstInput.requestFocus();
            }
        });
    }

    /** The screen fades out; only then does the next screen open. */
    public void leave(Runnable next) {
        layout.setDisable(true);
        FadeTransition fade = new FadeTransition(FADE, layout);
        fade.setToValue(0);
        fade.setOnFinished(e -> {
            dispose();
            next.run();
        });
        fade.play();
    }

    /** Lets go of the shared theme, for when this screen is replaced. */
    public void dispose() {
        theme.removeListener(themeListener);
    }
}
