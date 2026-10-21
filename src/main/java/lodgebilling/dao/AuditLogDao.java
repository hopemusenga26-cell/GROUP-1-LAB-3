package lodgebilling.dao;

import lodgebilling.model.AuditAction;
import lodgebilling.model.AuditEntry;

import java.util.List;

/** Append-only record of who did what and when. Nothing in the app edits or deletes it. */
public interface AuditLogDao {

    /** Writes one entry. A failure here must never stop the user's work. */
    void record(String username, AuditAction action, String details);

    /** The newest entries first. */
    List<AuditEntry> latest(int limit);
}
