package lodgebilling.model;

import java.util.regex.Pattern;

/**
 * The one rule for what a person's name may look like, shared by receipts and bookings
 * so the two screens can never disagree again.
 *
 * Words of letters (any alphabet: \p{L}), joined by ONE separator: a space, hyphen,
 * apostrophe, or a full stop that may be followed by a space. That allows initials:
 * "John M. Phiri", "J.M. Phiri", "Mary O'Neil-Banda", "Phiri Jr." but still refuses
 * digits, a leading separator or two separators in a row ("John  -Phiri").
 */
public final class PersonName {

    private static final Pattern VALID =
            Pattern.compile("\\p{L}+(?:(?:\\. ?|[ '’\\-])\\p{L}+)*\\.?");

    private PersonName() { }

    public static boolean isValid(String name) {
        return name != null && VALID.matcher(name).matches();
    }
}
