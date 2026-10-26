package lodgebilling.view;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import lodgebilling.controller.BillingActions;
import lodgebilling.model.BillingModel;
import lodgebilling.model.User;

// Lab 3 - Group 1
/** The bar above each page: the page's name, who is signed in, theme switch, password and log out. */
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

        ThemeToggle theme = new ThemeToggle(model.themeProperty(), actions::toggleTheme);

        setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(pageTitle, Ui.spacer(), who, role, theme,
                Ui.compact("Password", actions::changePassword), Ui.compact("Log out", actions::logout));
        getStyleClass().add("header");
    }

    public void setPage(String name) {
        pageTitle.setText(name);
    }
}
