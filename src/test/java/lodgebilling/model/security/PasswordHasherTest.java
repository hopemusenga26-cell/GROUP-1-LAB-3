package lodgebilling.model.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher(1_000);   // low count: fast tests

    @Test
    void rightPasswordVerifiesWrongOneDoesNot() {
        String stored = hasher.hash("secret123".toCharArray());
        assertTrue(hasher.verify("secret123".toCharArray(), stored));
        assertFalse(hasher.verify("secret124".toCharArray(), stored));
    }

    @Test
    void samePasswordHashesDifferentlyEachTime() {
        // Random salt: two users with the same password do not get the same hash
        assertNotEquals(hasher.hash("secret123".toCharArray()), hasher.hash("secret123".toCharArray()));
    }

    @Test
    void storedValueNeverContainsThePassword() {
        assertFalse(hasher.hash("secret123".toCharArray()).contains("secret123"));
    }

    @Test
    void malformedStoredValuesNeverMatch() {
        assertFalse(hasher.verify("x".toCharArray(), null));
        assertFalse(hasher.verify("x".toCharArray(), "plain-text"));
        assertFalse(hasher.verify("x".toCharArray(), "pbkdf2$abc$!!$!!"));
    }

    @Test
    void policyNeedsLengthLetterAndDigit() {
        assertNotNull(PasswordPolicy.problemWith("short1".toCharArray()));
        assertNotNull(PasswordPolicy.problemWith("lettersonly".toCharArray()));
        assertNotNull(PasswordPolicy.problemWith("12345678".toCharArray()));
        assertNull(PasswordPolicy.problemWith("lodge2026".toCharArray()));
    }

    @Test
    void staffCannotDoAdminThings() {
        assertTrue(Role.STAFF.can(Permission.CREATE_BILL));
        assertFalse(Role.STAFF.can(Permission.DELETE_BILL));
        assertFalse(Role.STAFF.can(Permission.MANAGE_USERS));
        for (Permission p : Permission.values()) {
            assertTrue(Role.ADMIN.can(p), "admin should have " + p);
        }
    }
}
