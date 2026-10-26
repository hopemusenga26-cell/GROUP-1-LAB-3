package lodgebilling.view;

import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;
import lodgebilling.controller.BillingActions;
import lodgebilling.model.BillingModel;
import lodgebilling.model.Theme;
import lodgebilling.model.User;
import lodgebilling.model.security.Permission;

// Lab 3 - Group 1
/** The bar above each page: the page's name, the search box, who is signed in, and the Settings menu. */
public class AppHeader extends HBox {

    private final Label pageTitle = new Label();

    /** @param onSearch opens the search window, or null if this role does not handle stays */
    public AppHeader(BillingModel model, BillingActions actions, Runnable onSearch) {
        super(12);
        User user = model.session().user();
        pageTitle.getStyleClass().add("page-title");

        Label who = new Label(user.fullName());
        who.getStyleClass().add("user-badge");
        Label role = new Label(user.role().getLabel());
        role.getStyleClass().add("user-badge-role");

        setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(pageTitle, Ui.spacer());
        if (onSearch != null) {
            getChildren().add(searchBox(onSearch));
        }
        getChildren().addAll(who, role, settings(model, actions));
        getStyleClass().add("header");
    }

    /** Looks like a search field, so it is found without being told; opens the search window. */
    private static Button searchBox(Runnable onSearch) {
        Label prompt = new Label("Search guests and bookings");
        prompt.getStyleClass().add("search-prompt");
        Label keys = new Label("Ctrl+K");
        keys.getStyleClass().add("search-keys");
        HBox face = new HBox(prompt, Ui.spacer(), keys);
        face.setAlignment(Pos.CENTER_LEFT);
        Button box = Ui.button("Search", onSearch);
        box.setGraphic(face);
        box.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        box.getStyleClass().add("search-box");
        box.setPrefWidth(280);
        box.setMinWidth(USE_PREF_SIZE);
        return box;
    }

    /** Everything about the session and the app in one place, so the header stays quiet. */
    private static MenuButton settings(BillingModel model, BillingActions actions) {
        // Names the theme it switches to, like the switch on the sign-in screen
        MenuItem theme = item("", actions::toggleTheme);
        theme.textProperty().bind(Bindings.when(model.themeProperty().isEqualTo(Theme.DARK))
                .then("Light mode").otherwise("Dark mode"));

        MenuButton menu = new MenuButton("Settings", null, theme, item("Change password", actions::changePassword));
        if (model.session().has(Permission.MANAGE_BACKUPS)) {
            menu.getItems().addAll(new SeparatorMenuItem(),
                    item("Back up now", actions::backupNow),
                    item("Restore a backup...", actions::restoreBackup));
        }
        menu.getItems().addAll(new SeparatorMenuItem(),
                item("Log out", actions::logout),
                item("Exit", actions::exit));
        menu.getStyleClass().add("settings-menu");
        menu.setMinWidth(USE_PREF_SIZE);
        return menu;
    }

    private static MenuItem item(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(e -> action.run());
        return item;
    }

    public void setPage(String name) {
        pageTitle.setText(name);
    }
}
