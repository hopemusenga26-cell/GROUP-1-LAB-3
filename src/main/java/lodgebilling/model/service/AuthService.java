package lodgebilling.model.service;

import lodgebilling.dao.AuditLogDao;
import lodgebilling.model.AuditAction;
import lodgebilling.dao.UserDao;
import lodgebilling.dao.UserDao.Credentials;
import lodgebilling.model.User;
import lodgebilling.model.security.PasswordHasher;
import lodgebilling.model.security.PasswordPolicy;
import lodgebilling.model.security.Permission;
import lodgebilling.model.security.Role;
import lodgebilling.model.security.Session;
import lodgebilling.model.security.TemporaryPassword;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Everything about accounts: first-run setup, login with lock-out, passwords,
 * and the admin-only user management. The screens call this; they never touch
 * the users table or password hashes directly.
 */
public class AuthService {

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration LOCKOUT = Duration.ofMinutes(5);

    private static final String USERNAME_PATTERN = "[A-Za-z0-9._]{3,20}";
    private static final String BAD_LOGIN = "Wrong username or password.";

    private final UserDao users;
    private final AuditLogDao audit;
    private final PasswordHasher hasher;
    private final Clock clock;
    // Hashed once, then checked against when the username does not exist, so a
    // wrong username takes as long as a wrong password (no timing hint about which)
    private final String dummyHash;
    private final TemporaryPassword temporaryPasswords = new TemporaryPassword();

    public AuthService(UserDao users, AuditLogDao audit, PasswordHasher hasher, Clock clock) {
        this.users = users;
        this.audit = audit;
        this.hasher = hasher;
        this.clock = clock;
        this.dummyHash = hasher.hash("not-a-real-password-1".toCharArray());
    }

    // ---------- First run ----------

    /** True until the first account exists. */
    public boolean needsSetup() {
        return users.count() == 0;
    }

    /** Creates the first admin. Refused once any account exists, so it cannot be used to sneak in. */
    public User createFirstAdmin(String username, String fullName, char[] password) throws AuthException {
        if (!needsSetup()) {
            throw new AuthException("Setup has already been done. Sign in instead.");
        }
        User admin = createAccount(username, fullName, Role.ADMIN, password, false);
        audit.record(admin.username(), AuditAction.SETUP, "Created the first admin account");
        return admin;
    }

    // ---------- Login ----------

    public User login(String username, char[] password) throws AuthException {
        try {
            String name = username == null ? "" : username.trim();
            if (name.isEmpty() || password == null || password.length == 0) {
                throw new AuthException("Enter your username and password.");
            }

            Optional<Credentials> found = users.findCredentials(name);
            if (found.isEmpty()) {
                hasher.verify(password, dummyHash);   // same work as a real check
                audit.record(name, AuditAction.LOGIN_FAILED, "Unknown username");
                throw new AuthException(BAD_LOGIN);
            }

            Credentials c = found.get();
            User user = c.user();
            Instant now = Instant.now(clock);

            if (c.lockedUntil() != null && now.isBefore(c.lockedUntil())) {
                long minutes = Math.max(1, Duration.between(now, c.lockedUntil()).toMinutes() + 1);
                throw new AuthException("Too many wrong passwords. Try again in " + minutes + " minute(s), "
                        + "or ask an admin to reset your password.");
            }

            if (!hasher.verify(password, c.passwordHash())) {
                int failed = c.failedAttempts() + 1;
                if (failed >= MAX_FAILED_ATTEMPTS) {
                    users.recordFailedLogin(user.id(), 0, now.plus(LOCKOUT));
                    audit.record(user.username(), AuditAction.ACCOUNT_LOCKED,
                            "Locked for " + LOCKOUT.toMinutes() + " minutes after " + failed + " wrong passwords");
                } else {
                    users.recordFailedLogin(user.id(), failed, null);
                    audit.record(user.username(), AuditAction.LOGIN_FAILED, "Wrong password (" + failed + ")");
                }
                throw new AuthException(BAD_LOGIN);
            }

            // Checked only after the password is right, so this does not reveal which accounts exist
            if (!user.active()) {
                audit.record(user.username(), AuditAction.LOGIN_FAILED, "Account is disabled");
                throw new AuthException("This account has been disabled. Ask an admin.");
            }

            users.recordSuccessfulLogin(user.id(), now);
            audit.record(user.username(), AuditAction.LOGIN, "");
            return user;
        } finally {
            PasswordHasher.wipe(password);
        }
    }

    public void logout(Session session) {
        audit.record(session.username(), AuditAction.LOGOUT, "");
    }

    // ---------- Own password ----------

    /** Returns the updated user (with mustChangePassword cleared). */
    public User changeOwnPassword(Session session, char[] current, char[] replacement) throws AuthException {
        try {
            Credentials c = users.findCredentials(session.username())
                    .orElseThrow(() -> new AuthException("Your account no longer exists."));
            if (!hasher.verify(current, c.passwordHash())) {
                throw new AuthException("Your current password is wrong.");
            }
            if (hasher.verify(replacement, c.passwordHash())) {
                throw new AuthException("Choose a password different from the current one.");
            }
            checkPolicy(replacement);
            users.updatePassword(c.user().id(), hasher.hash(replacement), false);
            audit.record(session.username(), AuditAction.PASSWORD_CHANGED, "");
            return users.findById(c.user().id()).orElseThrow();
        } finally {
            PasswordHasher.wipe(current);
            PasswordHasher.wipe(replacement);
        }
    }

    // ---------- Admin: user management ----------

    public List<User> listUsers(Session session) {
        session.require(Permission.MANAGE_USERS);
        return users.findAll();
    }

    /** The new user must choose their own password at first login. */
    public User createUser(Session session, String username, String fullName, Role role, char[] tempPassword)
            throws AuthException {
        session.require(Permission.MANAGE_USERS);
        User created = createAccount(username, fullName, role, tempPassword, true);
        audit.record(session.username(), AuditAction.USER_CREATED, created.username() + " as " + role.getLabel());
        return created;
    }

    /**
     * A new account and the one-time password the app made for it. The admin is shown
     * the password once to pass on; the caller should wipe it after showing it.
     */
    public record IssuedAccount(User user, char[] temporaryPassword) { }

    /** Creates an account with a password the app generates (the admin does not invent one). */
    public IssuedAccount createUser(Session session, String username, String fullName, Role role)
            throws AuthException {
        char[] temp = temporaryPasswords.next();
        // createUser wipes what it is given, so hand it a copy and keep the original to show
        User created = createUser(session, username, fullName, role, temp.clone());
        return new IssuedAccount(created, temp);
    }

    /** Resets to a generated one-time password and returns it, for the admin to pass on. */
    public char[] resetPassword(Session session, long userId) throws AuthException {
        char[] temp = temporaryPasswords.next();
        try {
            resetPassword(session, userId, temp.clone());
            return temp;
        } catch (AuthException | RuntimeException e) {
            PasswordHasher.wipe(temp);
            throw e;
        }
    }

    public void resetPassword(Session session, long userId, char[] tempPassword) throws AuthException {
        session.require(Permission.MANAGE_USERS);
        try {
            User target = existing(userId);
            checkPolicy(tempPassword);
            users.updatePassword(userId, hasher.hash(tempPassword), true);
            audit.record(session.username(), AuditAction.PASSWORD_RESET, "for " + target.username());
        } finally {
            PasswordHasher.wipe(tempPassword);
        }
    }

    public void setActive(Session session, long userId, boolean active) throws AuthException {
        session.require(Permission.MANAGE_USERS);
        User target = existing(userId);
        if (target.id() == session.user().id()) {
            throw new AuthException("You cannot disable your own account.");
        }
        if (!active && target.role() == Role.ADMIN && target.active() && users.countActiveAdmins() <= 1) {
            throw new AuthException("You cannot disable the last active admin.");
        }
        users.setActive(userId, active);
        audit.record(session.username(), active ? AuditAction.USER_ENABLED : AuditAction.USER_DISABLED, target.username());
    }

    public void setRole(Session session, long userId, Role role) throws AuthException {
        session.require(Permission.MANAGE_USERS);
        User target = existing(userId);
        if (target.id() == session.user().id()) {
            throw new AuthException("You cannot change your own role. Ask another admin.");
        }
        if (target.role() == role) {
            return;
        }
        if (target.role() == Role.ADMIN && target.active() && users.countActiveAdmins() <= 1) {
            throw new AuthException("You cannot demote the last active admin.");
        }
        users.setRole(userId, role);
        audit.record(session.username(), AuditAction.ROLE_CHANGED,
                target.username() + ": " + target.role().getLabel() + " -> " + role.getLabel());
    }

    // ---------- Helpers ----------

    private User createAccount(String username, String fullName, Role role, char[] password, boolean mustChange)
            throws AuthException {
        try {
            String name = username == null ? "" : username.trim();
            String full = fullName == null ? "" : fullName.trim().replaceAll("\\s+", " ");
            if (!name.matches(USERNAME_PATTERN)) {
                throw new AuthException("Usernames are 3 to 20 letters, numbers, dots or underscores.");
            }
            if (full.length() < 2 || full.length() > 60) {
                throw new AuthException("Enter the person's full name (2 to 60 characters).");
            }
            if (role == null) {
                throw new AuthException("Choose a role.");
            }
            if (users.usernameExists(name)) {
                throw new AuthException("The username \"" + name + "\" is already taken.");
            }
            checkPolicy(password);
            return users.insert(name, full, role, hasher.hash(password), mustChange, Instant.now(clock));
        } finally {
            PasswordHasher.wipe(password);
        }
    }

    private User existing(long userId) throws AuthException {
        return users.findById(userId).orElseThrow(() -> new AuthException("That user no longer exists."));
    }

    private static void checkPolicy(char[] password) throws AuthException {
        String problem = PasswordPolicy.problemWith(password);
        if (problem != null) {
            throw new AuthException(problem);
        }
    }

}
