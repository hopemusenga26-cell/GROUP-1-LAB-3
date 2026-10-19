package lodgebilling.view;

import javafx.beans.property.ObjectProperty;
import lodgebilling.model.Theme;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/** The sign-in screen shown at start-up and after logging out. */
public class LoginView extends AuthCard {

    /** Called with what was typed. The password array is wiped by the receiver. */
    @FunctionalInterface
    public interface Handler {
        void signIn(String username, char[] password);
    }

    private final TextField username;
    private final PasswordField password;

    public LoginView(Handler handler, ObjectProperty<Theme> theme) {
        super("Sign in", "Use the account your admin gave you.", theme);
        username = field("Username", new TextField());
        password = field("Password", new PasswordField());
        Button signIn = new Button("Sign in");
        signIn.setOnAction(e -> {
            clearError();
            char[] typed = password.getText().toCharArray();
            password.clear();   // never leave a password sitting in the field
            handler.signIn(username.getText(), typed);
        });
        finish(signIn);
        // There is no sign-up on purpose: only an admin creates accounts and chooses their role
        footnote("No account? Ask your manager to create one for you. "
                + "Forgot your password? An admin can reset it.");
    }

    @Override
    public void showError(String message) {
        super.showError(message);
        password.requestFocus();
    }
}
