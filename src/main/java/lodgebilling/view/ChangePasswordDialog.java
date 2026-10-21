package lodgebilling.view;

import javafx.scene.control.PasswordField;
import javafx.stage.Window;
import lodgebilling.model.Theme;
import lodgebilling.model.security.PasswordPolicy;
import lodgebilling.model.service.AuthException;

/** Asks for the current password and a new one (typed twice). */
public final class ChangePasswordDialog {

    /** Performs the change; throws with a message if it is refused. */
    @FunctionalInterface
    public interface Change {
        void apply(char[] current, char[] replacement) throws AuthException;
    }

    private ChangePasswordDialog() { }

    /**
     * @param forced true at first login after an admin set a temporary password:
     *               the only way out without changing it is to log out
     * @return true if the password was changed
     */
    public static boolean show(Window owner, Theme theme, boolean forced, Change change) {
        FormDialog form = new FormDialog(owner, theme, "Change password",
                forced ? "Choose your own password before you continue." : "Change your password",
                "Change password", forced ? "Log out" : "Cancel");
        PasswordField current = form.add(forced ? "Temporary password" : "Current password", new PasswordField());
        PasswordField replacement = form.add("New password", new PasswordField());
        PasswordField confirm = form.add("Repeat new password", new PasswordField());
        form.addNote("At least " + PasswordPolicy.MIN_LENGTH + " characters, with a letter and a number.");

        form.onSubmit(() -> {
            if (!replacement.getText().equals(confirm.getText())) {
                throw new AuthException("The two new passwords do not match.");
            }
            // JavaFX only gives the text as a String; convert straight away and clear the fields
            change.apply(current.getText().toCharArray(), replacement.getText().toCharArray());
            current.clear();
            replacement.clear();
            confirm.clear();
        });
        return form.showAndWait();
    }
}
