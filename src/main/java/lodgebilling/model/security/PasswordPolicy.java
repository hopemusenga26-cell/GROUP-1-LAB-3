package lodgebilling.model.security;

/** The rules a new password must meet. Returns an error message, or null if it is fine. */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 128;

    private PasswordPolicy() { }

    public static String problemWith(char[] password) {
        if (password == null || password.length < MIN_LENGTH) {
            return "The password must be at least " + MIN_LENGTH + " characters.";
        }
        if (password.length > MAX_LENGTH) {
            return "The password must be at most " + MAX_LENGTH + " characters.";
        }
        boolean letter = false;
        boolean digit = false;
        for (char c : password) {
            letter |= Character.isLetter(c);
            digit |= Character.isDigit(c);
        }
        if (!letter || !digit) {
            return "The password must contain at least one letter and one number.";
        }
        return null;
    }
}
