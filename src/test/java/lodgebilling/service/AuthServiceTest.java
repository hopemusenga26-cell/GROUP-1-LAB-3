package lodgebilling.service;

import lodgebilling.data.AuditLog;
import lodgebilling.data.Database;
import lodgebilling.data.UserRepository;
import lodgebilling.model.User;
import lodgebilling.security.AccessDeniedException;
import lodgebilling.security.PasswordHasher;
import lodgebilling.security.Role;
import lodgebilling.security.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {

    /** A clock the test can move forward, to check the lock-out expires. */
    static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-11T08:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private Database db;
    private TestClock clock;
    private AuthService auth;
    private AuditLog audit;
    private Session admin;

    private static char[] pw(String s) {
        return s.toCharArray();
    }

    @BeforeEach
    void setUp() throws AuthException {
        db = Database.inMemory();
        clock = new TestClock();
        audit = new AuditLog(db, clock);
        auth = new AuthService(new UserRepository(db), audit, new PasswordHasher(1_000), clock);
        assertTrue(auth.needsSetup());
        admin = new Session(auth.createFirstAdmin("admin", "Sammy Admin", pw("admin2026")));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void setupOnlyWorksOnce() {
        assertFalse(auth.needsSetup());
        assertThrows(AuthException.class, () -> auth.createFirstAdmin("evil", "Evil Admin", pw("evil20266")));
    }

    @Test
    void loginWithRightPasswordAndUsernameIgnoresCase() throws AuthException {
        User u = auth.login("ADMIN", pw("admin2026"));
        assertEquals(Role.ADMIN, u.role());
    }

    @Test
    void wrongPasswordAndUnknownUserGiveTheSameMessage() {
        String wrongPw = assertThrows(AuthException.class, () -> auth.login("admin", pw("nope12345"))).getMessage();
        String noUser = assertThrows(AuthException.class, () -> auth.login("ghost", pw("nope12345"))).getMessage();
        assertEquals(wrongPw, noUser);   // does not reveal which usernames exist
    }

    @Test
    void fiveWrongPasswordsLockTheAccountUntilTheLockExpires() throws AuthException {
        for (int i = 0; i < AuthService.MAX_FAILED_ATTEMPTS; i++) {
            assertThrows(AuthException.class, () -> auth.login("admin", pw("wrong1234")));
        }
        // Locked: even the right password is refused
        AuthException locked = assertThrows(AuthException.class, () -> auth.login("admin", pw("admin2026")));
        assertTrue(locked.getMessage().contains("Try again"));

        clock.advance(AuthService.LOCKOUT.plusSeconds(1));
        assertEquals("admin", auth.login("admin", pw("admin2026")).username());
    }

    @Test
    void newStaffMustChangeTemporaryPassword() throws AuthException {
        auth.createUser(admin, "mary", "Mary Banda", Role.STAFF, pw("temp1234"));
        User mary = auth.login("mary", pw("temp1234"));
        assertTrue(mary.mustChangePassword());

        User changed = auth.changeOwnPassword(new Session(mary), pw("temp1234"), pw("marys0wn"));
        assertFalse(changed.mustChangePassword());
        assertThrows(AuthException.class, () -> auth.login("mary", pw("temp1234")));
        assertEquals("mary", auth.login("mary", pw("marys0wn")).username());
    }

    @Test
    void staffCannotManageUsers() throws AuthException {
        User staff = auth.createUser(admin, "john", "John Phiri", Role.STAFF, pw("temp1234"));
        Session staffSession = new Session(staff);
        assertThrows(AccessDeniedException.class, () -> auth.listUsers(staffSession));
        assertThrows(AccessDeniedException.class,
                () -> auth.createUser(staffSession, "x_user", "X User", Role.ADMIN, pw("temp1234")));
    }

    @Test
    void disabledUserCannotSignIn() throws AuthException {
        User staff = auth.createUser(admin, "john", "John Phiri", Role.STAFF, pw("temp1234"));
        auth.setActive(admin, staff.id(), false);
        AuthException e = assertThrows(AuthException.class, () -> auth.login("john", pw("temp1234")));
        assertTrue(e.getMessage().contains("disabled"));
    }

    @Test
    void lastAdminCannotBeDisabledOrDemotedAndAdminCannotEditSelf() throws AuthException {
        long me = admin.user().id();
        assertThrows(AuthException.class, () -> auth.setActive(admin, me, false));
        assertThrows(AuthException.class, () -> auth.setRole(admin, me, Role.STAFF));

        User second = auth.createUser(admin, "boss2", "Second Admin", Role.ADMIN, pw("temp1234"));
        // With two admins, the other one can be demoted
        auth.setRole(admin, second.id(), Role.STAFF);
        assertEquals(Role.STAFF, auth.listUsers(admin).stream()
                .filter(u -> u.id() == second.id()).findFirst().orElseThrow().role());
    }

    @Test
    void badInputIsRejected() {
        assertThrows(AuthException.class, () -> auth.createUser(admin, "a", "Short Name", Role.STAFF, pw("temp1234")));
        assertThrows(AuthException.class, () -> auth.createUser(admin, "bob'; DROP TABLE users;--", "Bob",
                Role.STAFF, pw("temp1234")));
        assertThrows(AuthException.class, () -> auth.createUser(admin, "admin", "Dup", Role.STAFF, pw("temp1234")));
        assertThrows(AuthException.class, () -> auth.createUser(admin, "weak", "Weak Pw", Role.STAFF, pw("123")));
    }

    @Test
    void importantEventsAreAudited() throws AuthException {
        auth.login("admin", pw("admin2026"));
        assertThrows(AuthException.class, () -> auth.login("admin", pw("wrong1234")));
        var actions = audit.latest(10).stream().map(AuditLog.Entry::action).toList();
        assertTrue(actions.contains(AuditLog.Action.SETUP));
        assertTrue(actions.contains(AuditLog.Action.LOGIN));
        assertTrue(actions.contains(AuditLog.Action.LOGIN_FAILED));
    }

    @Test
    void theAppMakesTheTemporaryPasswordAndItWorksOnlyUntilReplaced() throws AuthException {
        AuthService.IssuedAccount issued = auth.createUser(admin, "mary", "Mary Banda", Role.STAFF);
        String temp = new String(issued.temporaryPassword());
        assertTrue(temp.matches("[a-z2-9]{4}-[a-z2-9]{4}-[a-z2-9]{4}"), temp);

        User mary = auth.login("mary", temp.toCharArray());
        assertTrue(mary.mustChangePassword());
        auth.changeOwnPassword(new Session(mary), temp.toCharArray(), pw("marys0wn"));
        assertThrows(AuthException.class, () -> auth.login("mary", temp.toCharArray()));
    }

    @Test
    void generatedPasswordsAreDifferentEachTime() throws AuthException {
        String a = new String(auth.createUser(admin, "mary", "Mary Banda", Role.STAFF).temporaryPassword());
        String b = new String(auth.createUser(admin, "joe", "Joe Mwale", Role.HOUSEKEEPING).temporaryPassword());
        assertFalse(a.equals(b));
    }

    @Test
    void resetGivesANewOneTimePasswordAndTheOldOneStopsWorking() throws AuthException {
        User mary = auth.createUser(admin, "mary", "Mary Banda", Role.STAFF, pw("temp1234"));
        String fresh = new String(auth.resetPassword(admin, mary.id()));

        assertThrows(AuthException.class, () -> auth.login("mary", pw("temp1234")));
        assertTrue(auth.login("mary", fresh.toCharArray()).mustChangePassword());
    }

    @Test
    void onlyAnAdminCanIssuePasswords() throws AuthException {
        User staff = auth.createUser(admin, "mary", "Mary Banda", Role.STAFF, pw("temp1234"));
        Session desk = new Session(staff);
        assertThrows(AccessDeniedException.class, () -> auth.createUser(desk, "x_user", "X User", Role.STAFF));
        assertThrows(AccessDeniedException.class, () -> auth.resetPassword(desk, staff.id()));
    }

    @Test
    void theStaffListShowsWhenEachPersonLastSignedIn() throws AuthException {
        auth.createUser(admin, "mary", "Mary Banda", Role.STAFF, pw("temp1234"));
        User before = auth.listUsers(admin).stream().filter(u -> u.username().equals("mary")).findFirst().orElseThrow();
        assertEquals(null, before.lastLogin());   // never signed in yet

        clock.advance(Duration.ofHours(2));
        auth.login("mary", pw("temp1234"));
        User after = auth.listUsers(admin).stream().filter(u -> u.username().equals("mary")).findFirst().orElseThrow();
        assertEquals(clock.instant(), after.lastLogin());
        assertEquals("Must set own password", after.getStatus());
    }
}
