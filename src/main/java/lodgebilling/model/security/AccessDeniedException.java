package lodgebilling.model.security;

/** Thrown when the signed-in user tries something their role does not allow. */
public class AccessDeniedException extends RuntimeException {
    public AccessDeniedException(String message) {
        super(message);
    }
}
