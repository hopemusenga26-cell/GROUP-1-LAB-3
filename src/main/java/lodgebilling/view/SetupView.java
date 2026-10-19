package lodgebilling.view;

import javafx.beans.property.ObjectProperty;
import lodgebilling.model.Theme;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import lodgebilling.security.PasswordPolicy;

/**
 * First run only: there are no accounts yet, so the person installing the app
 * creates the first admin. There is no built-in default password to forget to change.
 */
public class SetupView extends AuthCard {

    @FunctionalInterface
    public interface Handler {
        void createAdmin(String fullName, String username, char[] password);
    }

    public SetupView(Handler handler, ObjectProperty<Theme> theme) {
        super("Create the admin account",
                "Passwords need " + PasswordPolicy.MIN_LENGTH + "+ characters, with a letter and a number.", theme);
        TextField fullName = field("Your full name", new TextField());
        TextField username = field("Username", new TextField());
        PasswordField password = field("Password", new PasswordField());
        PasswordField confirm = field("Repeat password", new PasswordField());

        Button create = new Button("Create admin account");
        create.setOnAction(e -> {
            clearError();
            if (!password.getText().equals(confirm.getText())) {
                showError("The two passwords do not match.");
                return;
            }
            handler.createAdmin(fullName.getText(), username.getText(), password.getText().toCharArray());
        });
        finish(create);
    }
}
