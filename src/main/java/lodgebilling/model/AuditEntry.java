package lodgebilling.model;

import java.time.Instant;

/** One row of the audit log: who did what and when, with JavaBean getters for the table. */
public record AuditEntry(Instant at, String username, AuditAction action, String details) {
    public Instant getAt()           { return at; }
    public String getUsername()      { return username; }
    public AuditAction getAction()   { return action; }
    public String getDetails()       { return details; }
}
