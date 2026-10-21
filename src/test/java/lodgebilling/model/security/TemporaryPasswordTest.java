package lodgebilling.model.security;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class TemporaryPasswordTest {

    @Test
    void everyPasswordPassesThePolicyAndAvoidsLookAlikeCharacters() {
        TemporaryPassword gen = new TemporaryPassword();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            char[] pw = gen.next();
            assertNull(PasswordPolicy.problemWith(pw));
            String s = new String(pw);
            assertEquals(14, s.length());
            assertFalse(s.matches(".*[0o1li].*"), "look-alike character in " + s);
            seen.add(s);
        }
        assertEquals(2_000, seen.size());   // no repeats
    }
}
