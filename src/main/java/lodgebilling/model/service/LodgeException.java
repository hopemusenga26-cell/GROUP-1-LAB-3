package lodgebilling.model.service;

/** A front-desk rule was broken (room taken, guest still owes money...). */
public class LodgeException extends UserFacingException {
    public LodgeException(String message) {
        super(message);
    }
}
