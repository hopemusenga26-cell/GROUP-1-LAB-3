package lodgebilling.model.security;

import lodgebilling.model.User;

import java.util.Objects;

/** Who is signed in right now. Created at login and thrown away at logout. */
public final class Session {

    private final User user;

    public Session(User user) {
        this.user = Objects.requireNonNull(user, "user");
    }

    public User user() {
        return user;
    }

    public String username() {
        return user.username();
    }

    public boolean has(Permission permission) {
        return user.role().can(permission);
    }

    /**
     * The real security check. Hiding a button is only a convenience; every
     * protected operation calls this, so a hidden feature cannot be reached
     * through a keyboard shortcut or a future code path either.
     */
    public void require(Permission permission) {
        if (!has(permission)) {
            throw new AccessDeniedException("Your account (" + user.role().getLabel()
                    + ") is not allowed to do that. Ask an admin.");
        }
    }
}
