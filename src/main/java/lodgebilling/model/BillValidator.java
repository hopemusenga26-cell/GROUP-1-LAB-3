package lodgebilling.model;


import java.math.BigDecimal;

/** All the form validation rules, in one place so they are easy to find and change. */
public class BillValidator {

    private static final int NAME_MIN_LENGTH = 2;
    private static final int NAME_MAX_LENGTH = 50;
    public static final int MAX_NIGHTS = 30;
    // Whole number, or up to 2 decimal places: 200, 150.5, 150.50
    private static final String MONEY_PATTERN = "\\d{1,7}(\\.\\d{1,2})?";

    /** Validates using the original fixed prices (kept for tests and simple use). */
    public BillInput validate(BillFormData data) throws ValidationException {
        return validate(data, RateCard.defaults());
    }

    /** Returns the validated input, or throws with a message and the field to fix. */
    public BillInput validate(BillFormData data, RateCard rates) throws ValidationException {
        // Clean the name: trim the ends and turn runs of spaces into one space
        String name = clean(data.name()).replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new ValidationException("Enter the guest name.", FormField.NAME);
        }
        if (name.length() < NAME_MIN_LENGTH || name.length() > NAME_MAX_LENGTH) {
            throw new ValidationException("The name must be " + NAME_MIN_LENGTH + " to "
                    + NAME_MAX_LENGTH + " characters long.", FormField.NAME);
        }
        if (!PersonName.isValid(name)) {
            throw new ValidationException(
                    "Use letters only in the name. Spaces, hyphens, apostrophes and initials (J. M.) are allowed.",
                    FormField.NAME);
        }

        RoomType room = data.room();
        if (room == null) {
            throw new ValidationException("Choose a room type.", FormField.ROOM);
        }

        Integer nights = parseNights(clean(data.nights()));
        if (nights == null) {
            throw new ValidationException(
                    "Nights must be a whole number from 1 to " + MAX_NIGHTS + ".", FormField.NIGHTS);
        }

        BigDecimal extras = parseMoney(clean(data.extras()));
        if (extras == null) {
            throw new ValidationException(
                    "Extras must be an amount like 200 or 150.50 (leave blank for 0).", FormField.EXTRAS);
        }

        BigDecimal paid = parseMoney(clean(data.paid()));
        if (paid == null) {
            throw new ValidationException(
                    "Amount paid must be an amount like 1000 or 500.50 (leave blank for 0).", FormField.PAID);
        }

        BillInput input = new BillInput(name, room, rates.rateFor(room), nights, extras, paid,
                clean(data.notes()));

        // Paid may not be more than the bill total
        Bill preview = Bill.of("", input);
        if (preview.getBalance().signum() < 0) {
            throw new ValidationException("Amount paid cannot be more than the total ("
                    + Money.format(preview.getTotal()) + ").", FormField.PAID);
        }
        return input;
    }

    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }

    private static Integer parseNights(String text) {
        if (!text.matches("\\d{1,3}")) {
            return null;
        }
        int nights = Integer.parseInt(text);
        return (nights >= 1 && nights <= MAX_NIGHTS) ? nights : null;
    }

    /** Blank means 0. Returns null if the text is not a valid amount. */
    private static BigDecimal parseMoney(String text) {
        if (text.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return text.matches(MONEY_PATTERN) ? new BigDecimal(text) : null;
    }
}
