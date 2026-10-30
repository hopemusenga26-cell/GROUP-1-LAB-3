package lodgebilling.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonNameTest {

    @ParameterizedTest
    @ValueSource(strings = {"John Phiri", "John M. Phiri", "J.M. Phiri", "J. M. Phiri", "Mary O'Neil-Banda",
            "Phiri Jr.", "Chileshe Mwansa-Bwalya", "José Ñúñez", "Mary O’Neil"})
    void realNamesAreAccepted(String name) {
        assertTrue(PersonName.isValid(name), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "John2", "John  -Phiri", "-John", "John..Phiri", "John. .Phiri", ". John",
            "John Phiri ", "<script>"})
    void junkIsRefused(String name) {
        assertFalse(PersonName.isValid(name), name);
    }
}
