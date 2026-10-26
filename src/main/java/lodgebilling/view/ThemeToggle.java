package lodgebilling.view;

import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.scene.control.Button;
import lodgebilling.model.Theme;

/**
 * The light/dark switch: a small button that names the theme it will switch to.
 * Its text is bound to the theme property, so changing the theme from anywhere
 * (e.g. Ctrl+T) updates it too.
 */
public class ThemeToggle extends Button {

    public ThemeToggle(ObjectProperty<Theme> theme, Runnable onToggle) {
        getStyleClass().addAll("compact", "theme-toggle");
        textProperty().bind(Bindings.when(theme.isEqualTo(Theme.DARK))
                .then("Light mode").otherwise("Dark mode"));
        accessibleTextProperty().bind(Bindings.when(theme.isEqualTo(Theme.DARK))
                .then("Dark mode is on. Switch to light mode.").otherwise("Light mode is on. Switch to dark mode."));
        setOnAction(e -> onToggle.run());
    }
}
