package lodgebilling.view;

import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;
import lodgebilling.controller.BillingActions;
import lodgebilling.model.BillingModel;
import lodgebilling.model.Theme;
import lodgebilling.model.User;

// Lab 3 - Group 1
/** The bar above each page: the page's name, who is signed in, and the Settings menu. */
public class AppHeader extends HBox {

    private final Label pageTitle = new Label();

    public AppHeader(BillingModel model, BillingActions actions) {
        super(12);
        User user = model.session().user();
        pageTitle.getStyleClass().add("page-title");

        Label who = new Label(user.fullName());
        who.getStyleClass().add("user-badge");
        Label role = new Label(user.role().getLabel());
        role.getStyleClass().add("user-badge-role");

        setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(pageTitle, Ui.spacer(), who, role, settings(model, actions));
        getStyleClass().add("header");
    }

    /** Everything about the session and the app in one place, so the header stays quiet. */
    private static MenuButton settings(BillingModel model, BillingActions actions) {
        // Names the theme it switches to, like the switch on the sign-in screen
        MenuItem theme = item("", actions::toggleTheme);
        theme.textProperty().bind(Bindings.when(model.themeProperty().isEqualTo(Theme.DARK))
                .then("Light mode").otherwise("Dark mode"));

        MenuButton menu = new MenuButton("Settings", null, theme,
                item("Change password", actions::changePassword),
                new SeparatorMenuItem(),
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
