package lodgebilling.model.service;

/** A refusal with a message written for the person at the desk, safe to show as-is. */
public class UserFacingException extends Exception {
    public UserFacingException(String message) {
        super(message);
    }
}
