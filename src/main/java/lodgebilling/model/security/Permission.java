package lodgebilling.model.security;

/**
 * Things a user may be allowed to do. Code checks permissions, never roles,
 * so adding a role only means editing {@link Role}.
 */
public enum Permission {
    // Receipts (the original walk-in billing screen)
    CREATE_BILL,
    PRINT_RECEIPT,
    DELETE_BILL,

    // Front desk
    VIEW_DASHBOARD,
    MANAGE_RESERVATIONS,   // book, edit, cancel, check in, check out (with zero balance)
    POST_FOLIO,            // add charges and record payments on a guest folio
    VOID_FOLIO_ENTRY,      // cancel a charge or payment posted by mistake
    FORCE_CHECKOUT,        // check a guest out who still owes money

    // Rooms
    UPDATE_ROOM_STATUS,    // clean / dirty / maintenance
    MANAGE_ROOMS,          // add rooms, change rates and VAT

    // End of day
    CASH_UP,               // count the drawer and close the day's takings
    REOPEN_DAY,            // undo a close, so a closed day's money can be changed again

    // Admin
    VIEW_REPORTS,
    MANAGE_USERS,
    VIEW_AUDIT_LOG,
    MANAGE_BACKUPS         // back up now and restore a backup
}
