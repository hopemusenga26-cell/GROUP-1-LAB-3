package lodgebilling.dao;

/** A database problem, unchecked so callers are not forced to handle SQLException everywhere. */
public class DataException extends RuntimeException {
    public DataException(String message, Throwable cause) {
        super(message, cause);
    }
}
