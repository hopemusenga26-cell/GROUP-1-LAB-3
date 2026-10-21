package lodgebilling.model.security;

import java.util.EnumSet;
import java.util.Set;

/** What kind of account a user has. Each role maps to a fixed set of permissions. */
public enum Role {
    /** Cleaners: see the rooms and mark them clean, dirty or under maintenance. Nothing financial. */
    HOUSEKEEPING("Housekeeping", EnumSet.of(Permission.UPDATE_ROOM_STATUS)),

    /** Front desk: bookings, check-in/out, folios and receipts, but no voids, deletes or settings. */
    STAFF("Front desk", EnumSet.of(
            Permission.CREATE_BILL, Permission.PRINT_RECEIPT,
            Permission.VIEW_DASHBOARD, Permission.MANAGE_RESERVATIONS, Permission.POST_FOLIO,
            Permission.UPDATE_ROOM_STATUS)),

    /** General manager: everything. */
    ADMIN("Admin", EnumSet.allOf(Permission.class));

    private final String label;
    private final Set<Permission> permissions;

    Role(String label, Set<Permission> permissions) {
        this.label = label;
        this.permissions = permissions;
    }

    public boolean can(Permission permission) {
        return permissions.contains(permission);
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
