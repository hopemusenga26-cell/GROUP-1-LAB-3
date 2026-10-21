package lodgebilling.model.service;

/** A login or account problem; the message is safe to show to the user as-is. */
public class AuthException extends UserFacingException {
    public AuthException(String message) {
        super(message);
    }
}
