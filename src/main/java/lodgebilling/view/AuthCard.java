package lodgebilling.view;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import lodgebilling.model.Theme;

/**
 * Shared look for the sign-in and first-run screens: a frosted card over the
 * aurora night sky, with the app's title, a column of labelled inputs, an error
 * line and one main button. The light/dark switch sits in the top-right corner.
 */
public abstract class AuthCard extends StackPane {

    private static final Duration EXIT_TIME = Duration.millis(450);

    private final VBox card = new VBox(10);
    private final Label error = new Label();
    private final AuroraBackground aurora = new AuroraBackground();
    private final ThemeToggle themeToggle;
    private final ObjectProperty<Theme> theme;
    private final ChangeListener<Theme> themeListener = (obs, old, now) -> Styles.apply(now, this);
    private TextField firstInput;

    /** The heading and the line under it are optional (null leaves them out). */
    AuthCard(String heading, String subheading, ObjectProperty<Theme> theme) {
        this.theme = theme;
        Label title = new Label("Lodge Billing");
        title.getStyleClass().add("app-title");
        card.getChildren().add(title);
        if (heading != null) {
            Label head = new Label(heading);
            head.getStyleClass().add("card-title");
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
        card.setPadding(new Insets(28));
        card.setMaxWidth(440);
        card.setMaxHeight(USE_PREF_SIZE);

        themeToggle = new ThemeToggle(theme, () -> theme.set(theme.get().toggled()));
        themeToggle.setFocusTraversable(false);
        StackPane.setAlignment(themeToggle, Pos.TOP_RIGHT);

        aurora.setManaged(false);
        Styles.apply(theme.get(), this);
        theme.addListener(themeListener);

        setPadding(new Insets(24));
        setAlignment(Pos.CENTER);
        getChildren().addAll(aurora, card, themeToggle);
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
        card.getChildren().addAll(error, main);
    }

    /** A line of small print under the main button. */
    protected void footnote(String text) {
        Label note = new Label(text);
        note.getStyleClass().add("hint");
        note.setWrapText(true);
        note.setMaxWidth(Double.MAX_VALUE);
        card.getChildren().add(note);
    }

    public void showError(String message) {
        error.setText(message);
        error.setVisible(true);
    }

    protected void clearError() {
        error.setVisible(false);
    }

    /** Call once the card is on screen: the entrance animation and the first focus. */
    public void install() {
        Motion.liftButtons(card);
        Motion.enter(card, themeToggle);
        Platform.runLater(() -> {
            if (firstInput != null) {
                firstInput.requestFocus();
            }
        });
    }

    /** The card shrinks and fades away; only then does the next screen open. */
    public void leave(Runnable next) {
        card.setDisable(true);
        themeToggle.setDisable(true);

        FadeTransition cardFade = new FadeTransition(EXIT_TIME, card);
        cardFade.setToValue(0);
        ScaleTransition cardShrink = new ScaleTransition(EXIT_TIME, card);
        cardShrink.setToX(0.9);
        cardShrink.setToY(0.9);
        FadeTransition toggleFade = new FadeTransition(EXIT_TIME, themeToggle);
        toggleFade.setToValue(0);

        ParallelTransition exit = new ParallelTransition(cardFade, cardShrink, toggleFade);
        exit.setInterpolator(Interpolator.EASE_IN);
        exit.setOnFinished(e -> {
            dispose();
            next.run();
        });
        exit.play();
    }

    /** Stops the animation and lets go of the shared theme, for when this screen is replaced. */
    public void dispose() {
        aurora.stop();
        theme.removeListener(themeListener);
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        // the backdrop ignores the padding and fills the whole window
        aurora.resizeRelocate(0, 0, getWidth(), getHeight());
    }
}
