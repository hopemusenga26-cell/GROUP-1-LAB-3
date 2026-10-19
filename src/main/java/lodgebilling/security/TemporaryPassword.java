package lodgebilling.security;

import java.security.SecureRandom;

/**
 * Makes the one-time passwords an admin hands to new staff, e.g. "k7mp-q4xr-9tbn".
 *
 * Why the app makes them instead of the admin: people asked to invent a password
 * pick "Password1" and reuse it for every new hire. These are random (SecureRandom,
 * not Random, which is predictable), and the user must replace one at first sign-in.
 *
 * 12 characters from a 31-letter alphabet is about 59 bits of randomness. Letters
 * that are easy to misread aloud or on paper (0/o, 1/l/i) are left out, and the
 * dashes make it easy to read out over the phone.
 */
public final class TemporaryPassword {

    private static final char[] ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final int GROUPS = 3;
    private static final int GROUP_LENGTH = 4;

    private final SecureRandom random;

    public TemporaryPassword() {
        this(new SecureRandom());
    }

    TemporaryPassword(SecureRandom random) {
        this.random = random;
    }

    /** A fresh password that always passes PasswordPolicy (it has a letter and a number). */
    public char[] next() {
        while (true) {
            char[] pw = new char[GROUPS * GROUP_LENGTH + GROUPS - 1];
            int i = 0;
            for (int g = 0; g < GROUPS; g++) {
                if (g > 0) {
                    pw[i++] = '-';
                }
                for (int c = 0; c < GROUP_LENGTH; c++) {
                    pw[i++] = ALPHABET[random.nextInt(ALPHABET.length)];
                }
            }
            if (PasswordPolicy.problemWith(pw) == null) {
                return pw;
            }
            PasswordHasher.wipe(pw);   // all letters or all digits: rare, just draw again
        }
    }
}
