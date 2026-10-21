package lodgebilling.view;

import javafx.beans.binding.Bindings;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCombination;
import lodgebilling.controller.BillingActions;
import lodgebilling.model.BillingModel;
import lodgebilling.model.Theme;
import lodgebilling.model.security.Permission;
import lodgebilling.model.security.Session;

import java.util.List;
import java.util.function.Consumer;

/**
 * The File, Go, View, Admin and Help menus, with their keyboard shortcuts.
 * Items the user's role cannot use are left out entirely (so their shortcuts
 * do nothing either); the services still check permissions.
 */
public class AppMenuBar extends MenuBar {

    public AppMenuBar(BillingActions actions, BillingModel model, List<AppPage> pages, Consumer<AppPage> go) {
        Session session = model.session();

        Menu file = new Menu("_File");
        if (session.has(Permission.CREATE_BILL)) {
            file.getItems().addAll(
                    item("_New guest", "Shortcut+N", actions::newGuest),
                    item("View _receipt", "Shortcut+R", actions::viewReceipt),
                    item("_Print receipt", "Shortcut+P", actions::printReceipt));
        }
        if (session.has(Permission.DELETE_BILL)) {
            file.getItems().add(item("_Delete selected receipt", "Shortcut+D", actions::deleteSelected));
        }
        if (!file.getItems().isEmpty()) {
            file.getItems().add(new SeparatorMenuItem());
        }
        file.getItems().addAll(
                item("Change pass_word...", null, actions::changePassword),
                item("_Log out", "Shortcut+L", actions::logout),
                item("_Close", "Shortcut+Q", actions::exit));

        // Ctrl+1, Ctrl+2... jump between pages without the mouse
        Menu goMenu = new Menu("_Go");
        for (int i = 0; i < pages.size(); i++) {
            AppPage p = pages.get(i);
            goMenu.getItems().add(item(p.label(), "Shortcut+" + (i + 1), () -> go.accept(p)));
        }

        MenuItem themeItem = item("", "Shortcut+T", actions::toggleTheme);
        themeItem.textProperty().bind(Bindings.when(model.themeProperty().isEqualTo(Theme.LIGHT))
                .then("Switch to _dark mode").otherwise("Switch to _light mode"));
        Menu view = new Menu("_View");
        view.getItems().add(themeItem);
        getMenus().addAll(file, goMenu, view);

        Menu admin = new Menu("_Admin");
        if (session.has(Permission.MANAGE_USERS)) {
            admin.getItems().add(item("Staff _accounts", "Shortcut+U", actions::manageUsers));
        }
        if (session.has(Permission.VIEW_AUDIT_LOG)) {
            admin.getItems().add(item("_Audit log...", null, actions::showAuditLog));
        }
        if (!admin.getItems().isEmpty()) {
            getMenus().add(admin);
        }

        Menu help = new Menu("_Help");
        help.getItems().add(item("_About", null, actions::showAbout));
        getMenus().add(help);
    }

    private static MenuItem item(String text, String shortcut, Runnable action) {
        MenuItem item = new MenuItem(text);
        if (shortcut != null) {
            item.setAccelerator(KeyCombination.keyCombination(shortcut));
        }
        item.setOnAction(e -> action.run());
        return item;
    }
}
