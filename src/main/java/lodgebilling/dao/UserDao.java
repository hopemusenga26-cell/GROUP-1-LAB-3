package lodgebilling.dao;

import lodgebilling.model.User;
import lodgebilling.model.security.Role;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Staff accounts, with the sign-in fields (password hash, failed attempts, lock-out). */
public interface UserDao {

    /** A user plus the security fields only the login code needs. */
    record Credentials(User user, String passwordHash, int failedAttempts, Instant lockedUntil) { }

    int count();

    int countActiveAdmins();

    Optional<Credentials> findCredentials(String username);

    Optional<User> findById(long id);

    /** Every account, grouped by role. */
    List<User> findAll();

    boolean usernameExists(String username);

    User insert(String username, String fullName, Role role, String passwordHash,
                boolean mustChangePassword, Instant now);

    void recordFailedLogin(long userId, int failedAttempts, Instant lockedUntil);

    void recordSuccessfulLogin(long userId, Instant at);

    /** A new password also clears any lock-out. */
    void updatePassword(long userId, String passwordHash, boolean mustChangePassword);

    void setActive(long userId, boolean active);

    void setRole(long userId, Role role);
}
