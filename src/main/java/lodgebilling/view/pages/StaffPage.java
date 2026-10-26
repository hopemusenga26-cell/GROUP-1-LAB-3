package lodgebilling.view.pages;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import lodgebilling.controller.PageContext;
import lodgebilling.model.User;
import lodgebilling.model.security.Permission;
import lodgebilling.model.security.Role;
import lodgebilling.model.service.AuthException;
import lodgebilling.model.service.AuthService;
import lodgebilling.view.AuditLogDialog;
import lodgebilling.view.FormDialog;
import lodgebilling.view.TemporaryPasswordDialog;
import lodgebilling.view.Ui;

import java.time.ZoneId;
import java.util.List;

/**
 * Admin-only page: everyone who can sign in, with their role, whether they still
 * have to set their own password, and when they last signed in. Admins add staff,
 * reset passwords, change roles and disable accounts here. There is no sign-up
 * anywhere else: this page is the only way an account comes to exist.
 *
 * Every action goes through AuthService, which checks MANAGE_USERS again, so the
 * page being hidden from other roles is convenience, not the security itself.
 */
public class StaffPage implements Page {

    private static final int AUDIT_ROWS_SHOWN = 500;

    static final String ROLES_EXPLAINED = "Housekeeping: room status only. "
            + "Front desk: bookings, check-in/out, folios and receipts. "
            + "Admin: everything, including prices, voids, reports and staff accounts.";

    private final PageContext ctx;
    private final AuthService auth;
    private final TableView<User> table = new TableView<>();
    private final Button reset = Ui.compact("Reset password", () -> withSelected(this::resetPassword));
    private final Button toggle = Ui.compact("Disable", () -> withSelected(this::toggleActive));
    private final Button role = Ui.compact("Change role", () -> withSelected(this::changeRole));
    private final VBox root;

    public StaffPage(PageContext ctx) {
        this.ctx = ctx;
        this.auth = ctx.auth();
        buildTable();

        Button add = Ui.compact("Add staff member", this::addUser);
        toggle.getStyleClass().add("danger");
        HBox actions = Ui.row(add, Ui.spacer(), reset, role, toggle);
        if (ctx.can(Permission.VIEW_AUDIT_LOG)) {
            // Who did what and when: sign-ins, payments, voids, account changes
            actions.getChildren().add(Ui.compact("Audit log", () -> ctx.attempt(() -> AuditLogDialog.show(
                    ctx.owner(), ctx.theme(), ctx.audit().latest(AUDIT_ROWS_SHOWN)))));
        }

        // The buttons only make sense with a row selected, and Disable/Enable follows the row
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> updateButtons(now));
        updateButtons(null);

        // How accounts work and what each role may do is explained in the Add and Change role forms
        VBox.setVgrow(table, Priority.ALWAYS);
        root = new VBox(12, actions, table);
        root.setPadding(new Insets(16, 20, 16, 20));
    }

    private void buildTable() {
        var name = Ui.column("Name", (User u) -> u.fullName() + (isMe(u) ? "  (you)" : ""));
        var username = Ui.column("Username", User::username);
        var roleCol = Ui.column("Role", (User u) -> u.role().getLabel());
        var status = Ui.column("Status", User::getStatus);
        var last = Ui.column("Last sign-in", (User u) -> u.lastLogin() == null ? "Never"
                : u.lastLogin().atZone(ZoneId.systemDefault()).format(Ui.DATE_TIME));
        name.setMinWidth(180);
        username.setMinWidth(120);
        roleCol.setMinWidth(110);
        status.setMinWidth(170);
        last.setMinWidth(120);
        table.getColumns().addAll(List.of(name, username, roleCol, status, last));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No accounts yet."));
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void refresh() {
        User selected = table.getSelectionModel().getSelectedItem();
        ctx.attempt(() -> {
            List<User> users = auth.listUsers(ctx.session());
            table.setItems(FXCollections.observableArrayList(users));
            if (selected != null) {
                users.stream().filter(u -> u.id() == selected.id()).findFirst()
                        .ifPresent(u -> table.getSelectionModel().select(u));
            }
        });
        updateButtons(table.getSelectionModel().getSelectedItem());
    }

    private boolean isMe(User u) {
        return u.id() == ctx.session().user().id();
    }

    private void updateButtons(User selected) {
        boolean none = selected == null;
        reset.setDisable(none);
        role.setDisable(none);
        toggle.setDisable(none);
        toggle.setText(selected != null && !selected.active() ? "Enable" : "Disable");
    }

    private interface UserAction {
        void run(User user) throws AuthException;
    }

    private void withSelected(UserAction action) {
        User selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            ctx.error("Select a person in the table first.");
            return;
        }
        ctx.attempt(() -> action.run(selected));
        refresh();
    }

    private void addUser() {
        FormDialog form = new FormDialog(ctx.owner(), ctx.theme(), "Add staff member", "Create an account",
                "Create account", "Cancel");
        TextField fullName = form.add("Full name", new TextField());
        TextField username = form.add("Username", new TextField());
        ComboBox<Role> roleBox = form.add("Role", new ComboBox<>(FXCollections.observableArrayList(Role.values())));
        roleBox.setValue(Role.STAFF);   // the most common hire; Admin is never the default
        form.addNote(ROLES_EXPLAINED);
        form.addNote("The app makes a one-time password for them. They choose their own at first sign-in.");
        AuthService.IssuedAccount[] issued = new AuthService.IssuedAccount[1];
        form.onSubmit(() -> {
            confirmAdmin(fullName.getText().isBlank() ? username.getText() : fullName.getText(), roleBox.getValue());
            issued[0] = auth.createUser(ctx.session(), username.getText(), fullName.getText(), roleBox.getValue());
        });
        if (form.showAndWait()) {
            User created = issued[0].user();
            ctx.success("Created " + created.username() + " (" + created.role().getLabel() + ").");
            refresh();
            TemporaryPasswordDialog.show(ctx.owner(), ctx.theme(), "Account created for " + created.fullName(),
                    created.username(), issued[0].temporaryPassword());
        }
    }

    private void resetPassword(User user) throws AuthException {
        if (!Ui.confirm(ctx.owner(), ctx.theme(), "Reset the password for " + user.fullName() + "?",
                "Their current password stops working and the app gives you a one-time password to pass on. "
                        + "This also unlocks the account if it was locked.", "Reset password")) {
            return;
        }
        char[] temp = auth.resetPassword(ctx.session(), user.id());
        ctx.success("Password reset for " + user.username() + ".");
        TemporaryPasswordDialog.show(ctx.owner(), ctx.theme(), "New password for " + user.fullName(),
                user.username(), temp);
    }

    private void toggleActive(User user) throws AuthException {
        boolean enable = !user.active();
        if (!enable && !Ui.confirm(ctx.owner(), ctx.theme(), "Disable " + user.fullName() + "?",
                "They will not be able to sign in. Their bookings, receipts and history stay as they are, "
                        + "and you can enable the account again later.", "Disable")) {
            return;
        }
        auth.setActive(ctx.session(), user.id(), enable);
        ctx.success((enable ? "Enabled " : "Disabled ") + user.username() + ".");
    }

    private void changeRole(User user) {
        FormDialog form = new FormDialog(ctx.owner(), ctx.theme(), "Change role", "Role for " + user.fullName(),
                "Change role", "Cancel");
        ComboBox<Role> roleBox = form.add("Role", new ComboBox<>(FXCollections.observableArrayList(Role.values())));
        roleBox.setValue(user.role());
        form.addNote(ROLES_EXPLAINED);
        form.onSubmit(() -> {
            if (user.role() != Role.ADMIN) {
                confirmAdmin(user.fullName(), roleBox.getValue());
            }
            auth.setRole(ctx.session(), user.id(), roleBox.getValue());
        });
        if (form.showAndWait()) {
            ctx.success(user.username() + " is now " + roleBox.getValue() + ".");
        }
    }

    /**
     * Giving someone Admin hands over the whole lodge, so it takes a second, deliberate
     * click instead of one slip in a dropdown. Cancelling keeps the form open.
     */
    private void confirmAdmin(String who, Role chosen) throws AuthException {
        if (chosen != Role.ADMIN) {
            return;
        }
        String name = who == null || who.isBlank() ? "this person" : who.trim();
        if (!Ui.confirm(ctx.owner(), ctx.theme(), "Make " + name + " an Admin?",
                "Admins can change prices and VAT, void charges, force check-outs, see every report, and "
                        + "create, reset or disable any staff account, including yours. "
                        + "For reception work choose Front desk instead.", "Make admin")) {
            throw new AuthException("Not saved. Choose another role, or confirm to give Admin access.");
        }
    }
}
