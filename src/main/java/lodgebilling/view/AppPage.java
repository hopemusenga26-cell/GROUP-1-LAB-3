package lodgebilling.view;

import lodgebilling.model.security.Permission;

/** The screens in the sidebar, in order, with who may see each one. */
public enum AppPage {
    DASHBOARD("Dashboard", Permission.VIEW_DASHBOARD),
    TAPE_CHART("Tape chart", Permission.MANAGE_RESERVATIONS),
    STAYS("Stays & folios", Permission.MANAGE_RESERVATIONS),
    RECEIPTS("Receipts", Permission.CREATE_BILL),
    ROOMS("Rooms", Permission.UPDATE_ROOM_STATUS),
    CASH_UP("End of day", Permission.CASH_UP),
    REPORTS("Reports", Permission.VIEW_REPORTS),
    STAFF("Staff", Permission.MANAGE_USERS);

    private final String label;
    private final Permission permission;

    AppPage(String label, Permission permission) {
        this.label = label;
        this.permission = permission;
    }

    public String label() {
        return label;
    }

    public Permission permission() {
        return permission;
    }
}
