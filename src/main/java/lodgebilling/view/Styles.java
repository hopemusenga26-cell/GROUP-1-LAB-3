package lodgebilling.view;

import javafx.scene.Parent;
import javafx.scene.text.Font;
import lodgebilling.model.Theme;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Where the stylesheet lives, the fonts it needs, and how a theme is applied to a window. */
public final class Styles {

    private static final String STYLESHEET = "/lodge.css";
    private static final String DARK_CLASS = "dark";
    // Bahnschrift (the DIN face of door plates and room numbers) ships with Windows 10 and 11.
    // JavaFX only finds it once the file is loaded; elsewhere the CSS falls back to the default font.
    private static final Path DISPLAY_FONT = Path.of(System.getenv().getOrDefault("WINDIR", "C:\\Windows"),
            "Fonts", "bahnschrift.ttf");
    private static boolean fontsLoaded;

    private Styles() { }

    public static String stylesheet() {
        loadFonts();
        return Styles.class.getResource(STYLESHEET).toExternalForm();
    }

    /** Adds or removes the "dark" style class that switches the CSS colour palette. */
    public static void apply(Theme theme, Parent root) {
        if (theme == Theme.DARK) {
            if (!root.getStyleClass().contains(DARK_CLASS)) {
                root.getStyleClass().add(DARK_CLASS);
            }
        } else {
            root.getStyleClass().remove(DARK_CLASS);
        }
    }

    private static synchronized void loadFonts() {
        if (fontsLoaded) {
            return;
        }
        fontsLoaded = true;
        if (Files.isReadable(DISPLAY_FONT)) {
            try (InputStream in = Files.newInputStream(DISPLAY_FONT)) {
                Font.loadFont(in, 14);
            } catch (IOException e) {
                // Only the look changes: headings and figures use the default font instead
            }
        }
    }
}
