package lodgebilling.model;

import lodgebilling.model.security.Role;

import java.time.Instant;

/**
 * A staff or admin account as the app sees it. The password hash is deliberately
 * not part of this record, so it can never end up in a table or a log by accident.
 *
 * @param lastLogin when they last signed in successfully, or null if they never have
 */
public record User(long id, String username, String fullName, Role role,
                   boolean active, boolean mustChangePassword, Instant lastLogin) {

    /** An account that has not signed in yet. */
    public User(long id, String username, String fullName, Role role, boolean active, boolean mustChangePassword) {
        this(id, username, fullName, role, active, mustChangePassword, null);
    }

    /** Getters in JavaBean style, for PropertyValueFactory in tables. */
    public String getUsername() { return username; }
    public String getFullName() { return fullName; }
    public Role getRole()       { return role; }

    public String getStatus() {
        if (!active) {
            return "Disabled";
        }
        // Still on the one-time password the admin gave them
        return mustChangePassword ? "Must set own password" : "Active";
    }
}
