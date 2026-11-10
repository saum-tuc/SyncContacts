package org.synccontacts.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.Optional;

/** Verifies conservative formatting normalization for phone-number matching. */
public final class PhoneNumbersTest {
    /** Common international prefixes and formatting produce the same key. */
    @Test
    public void normalizesFormattingAndInternationalPrefix() {
        assertEquals(Optional.of("+493012345678"), PhoneNumbers.key("+49 (30) 1234-5678"));
        assertEquals(PhoneNumbers.key("+49 30 12345678"), PhoneNumbers.key("0049 30 12345678"));
    }

    /** National forms are not guessed to represent a particular country. */
    @Test
    public void avoidsSuffixAndCountryGuesses() {
        assertFalse(PhoneNumbers.key("+49 30 12345678").equals(PhoneNumbers.key("030 12345678")));
        assertFalse(PhoneNumbers.key("+1 202 555 0140").equals(PhoneNumbers.key("+44 202 555 0140")));
    }

    /** Unsupported extension and pause syntax must not collapse to a different number. */
    @Test
    public void rejectsExtensionsAndInvalidNumbers() {
        for (String value : new String[]{"", "123", "+49 30 12345678 x12",
                "+49 30 12345678;12", "1234567890123456", "++49123456789"}) {
            assertFalse(PhoneNumbers.key(value).isPresent());
        }
    }
}
