
package lodgebilling.view;

import java.net.URL;
import java.util.Objects;

import javafx.scene.Parent;
import lodgebilling.model.Theme;

/**
 * Central styling manager for the Lodge Billing System.
 *
 * Loads the application's stylesheet and applies the selected
 * visual theme to JavaFX screens.
 */
public final class Styles {

    private static final String STYLESHEET = "/lodge.css";

    private static final String DARK_CLASS = "dark";

    private Styles() {
        // Utility class: prevent instantiation.
    }

    /**
     * Loads the main application stylesheet.
     *
     * @return stylesheet URL required by JavaFX
     * @throws IllegalStateException if the stylesheet cannot be found
     */
    public static String stylesheet() {
        URL resource = Styles.class.getResource(STYLESHEET);

        if (resource == null) {
            throw new IllegalStateException(
                    "Stylesheet not found: " + STYLESHEET
                            + ". Check that lodge.css is inside src/main/resources."
            );
        }

        return resource.toExternalForm();
    }

    /**
     * Applies the selected theme to a JavaFX root node.
     *
     * @param theme selected application theme
     * @param root root node of the screen or window
     */
    public static void apply(Theme theme, Parent root) {
        Objects.requireNonNull(theme, "Theme must not be null.");
        Objects.requireNonNull(root, "Root node must not be null.");

        if (theme == Theme.DARK) {
            if (!root.getStyleClass().contains(DARK_CLASS)) {
                root.getStyleClass().add(DARK_CLASS);
            }
        } else {
            root.getStyleClass().remove(DARK_CLASS);
        }
    }
}
