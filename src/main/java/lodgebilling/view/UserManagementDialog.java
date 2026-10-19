package lodgebilling.view;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import lodgebilling.model.Theme;
import lodgebilling.model.User;
import lodgebilling.security.Role;
import lodgebilling.security.Session;
import lodgebilling.service.AuthException;
import lodgebilling.service.AuthService;

/**
 * Admin-only window: list accounts, add staff or admins, reset passwords,
 * enable/disable accounts and change roles. Every action goes through
 * AuthService, which checks the permission again.
 */
public class UserManagementDialog {

    private final Window owner;
    private final Theme theme;
    private final AuthService auth;
    private final Session session;

    private final TableView<User> table = new TableView<>();
    private final Label status = new Label();

    public UserManagementDialog(Window owner, Theme theme, AuthService auth, Session session) {
        this.owner = owner;
        this.theme = theme;
        this.auth = auth;
        this.session = session;
    }

    public void showAndWait() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Manage users");
        dialog.setHeaderText("Staff and admin accounts");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        buildTable();
        Button add = new Button("Add user");
        Button reset = new Button("Reset password");
        Button toggle = new Button("Enable / disable");
        Button role = new Button("Change role");
        add.setOnAction(e -> addUser());
        reset.setOnAction(e -> withSelected(this::resetPassword));
        toggle.setOnAction(e -> withSelected(this::toggleActive));
        role.setOnAction(e -> withSelected(this::changeRole));

        status.getStyleClass().add("status");
        status.setWrapText(true);
        status.setMaxWidth(Double.MAX_VALUE);
        status.setText("Select a user, then choose an action.");

        VBox content = new VBox(12, new HBox(10, add, reset, toggle, role), table, status);
        content.setPadding(new Insets(10, 4, 0, 4));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getStylesheets().add(Styles.stylesheet());
        Styles.apply(theme, dialog.getDialogPane());
        refresh();
        dialog.showAndWait();
    }

    private void buildTable() {
        table.getColumns().add(column("Username", "username", 140));
        table.getColumns().add(column("Full name", "fullName", 200));
        table.getColumns().add(column("Role", "role", 90));
        table.getColumns().add(column("Status", "status", 90));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPrefSize(620, 300);
        table.setPlaceholder(new Label("No users."));
    }

    private static <T> TableColumn<User, T> column(String title, String property, double width) {
        TableColumn<User, T> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(property));
        col.setPrefWidth(width);
        return col;
    }

    private void refresh() {
        table.setItems(FXCollections.observableArrayList(auth.listUsers(session)));
    }

    private interface UserAction {
        void run(User user) throws AuthException;
    }

    private void withSelected(UserAction action) {
        User selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showStatus("Select a user first.", true);
            return;
        }
        try {
            action.run(selected);
        } catch (AuthException | RuntimeException e) {
            showStatus(e.getMessage(), true);
        }
    }

    private void addUser() {
        FormDialog form = new FormDialog(owner, theme, "Add user", "Create an account",
                "Create account", "Cancel");
        TextField fullName = form.add("Full name", new TextField());
        TextField username = form.add("Username", new TextField());
        ComboBox<Role> role = form.add("Role", new ComboBox<>(FXCollections.observableArrayList(Role.values())));
        role.setValue(Role.STAFF);   // the most common hire; Admin is never the default
        form.addNote(ROLES_EXPLAINED);
        form.addNote("The app makes a one-time password for them. They choose their own at first sign-in.");
        AuthService.IssuedAccount[] issued = new AuthService.IssuedAccount[1];
        form.onSubmit(() -> {
            confirmAdmin(fullName.getText().isBlank() ? username.getText() : fullName.getText(), role.getValue());
            issued[0] = auth.createUser(session, username.getText(), fullName.getText(), role.getValue());
        });
        if (form.showAndWait()) {
            User created = issued[0].user();
            showStatus("Created " + created.username() + " (" + created.role().getLabel() + ").", false);
            refresh();
            TemporaryPasswordDialog.show(owner, theme, "Account created for " + created.fullName(),
                    created.username(), issued[0].temporaryPassword());
        }
    }

    private void resetPassword(User user) {
        if (!confirm("Reset the password for " + user.username() + "? Their current password stops working "
                + "and the app gives you a one-time password to pass on. This also unlocks the account.")) {
            return;
        }
        char[] temp;
        try {
            temp = auth.resetPassword(session, user.id());
        } catch (AuthException e) {
            showStatus(e.getMessage(), true);
            return;
        }
        showStatus("Password reset for " + user.username() + ".", false);
        refresh();
        TemporaryPasswordDialog.show(owner, theme, "New password for " + user.fullName(), user.username(), temp);
    }

    private void toggleActive(User user) throws AuthException {
        boolean enable = !user.active();
        if (!enable && !confirm("Disable " + user.username() + "? They will not be able to sign in.")) {
            return;
        }
        auth.setActive(session, user.id(), enable);
        showStatus((enable ? "Enabled " : "Disabled ") + user.username() + ".", false);
        refresh();
    }

    private void changeRole(User user) {
        FormDialog form = new FormDialog(owner, theme, "Change role", "Role for " + user.username(),
                "Change role", "Cancel");
        ComboBox<Role> role = form.add("Role", new ComboBox<>(FXCollections.observableArrayList(Role.values())));
        role.setValue(user.role());
        form.addNote(ROLES_EXPLAINED);
        form.onSubmit(() -> {
            if (user.role() != Role.ADMIN) {
                confirmAdmin(user.fullName(), role.getValue());
            }
            auth.setRole(session, user.id(), role.getValue());
            showStatus(user.username() + " is now " + role.getValue() + ".", false);
        });
        if (form.showAndWait()) {
            refresh();
        }
    }

    private static final String ROLES_EXPLAINED = "Housekeeping: room status only. "
            + "Front desk: bookings, check-in/out, folios and receipts. "
            + "Admin: everything, including prices, voids, reports and staff accounts.";

    /**
     * Giving someone Admin hands over the whole lodge, so it takes a second, deliberate
     * click instead of one slip in a dropdown. Cancelling keeps the form open.
     */
    private void confirmAdmin(String who, Role chosen) throws AuthException {
        if (chosen != Role.ADMIN) {
            return;
        }
        String name = who == null || who.isBlank() ? "this person" : who.trim();
        if (!confirm("Make " + name + " an Admin? Admins can change prices and VAT, void charges, force "
                + "check-outs, see every report, and create, reset or disable any staff account, "
                + "including yours. For reception work choose Front desk instead.")) {
            throw new AuthException("Not saved. Choose another role, or confirm to give Admin access.");
        }
    }

    private boolean confirm(String question) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, question, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(owner);
        alert.setHeaderText(null);
        alert.getDialogPane().getStylesheets().add(Styles.stylesheet());
        Styles.apply(theme, alert.getDialogPane());
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    private void showStatus(String text, boolean error) {
        status.setText(text);
        status.getStyleClass().removeAll("error", "success");
        status.getStyleClass().add(error ? "error" : "success");
    }
}
