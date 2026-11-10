package org.synccontacts.app;

import java.util.Objects;
import java.util.Optional;

/** Provides conservative, country-independent phone-number matching keys. */
public final class PhoneNumbers {
    private PhoneNumbers() {
    }

    /**
     * Normalizes formatting only; national and international forms are not guessed
     * to be equivalent without a trustworthy country context.
     */
    public static Optional<String> key(String number) {
        Objects.requireNonNull(number, "number");
        String input = number.trim();
        StringBuilder digits = new StringBuilder();
        boolean international = false;
        for (int i = 0; i < input.length(); i++) {
            char character = input.charAt(i);
            int digit = Character.digit(character, 10);
            if (digit >= 0) {
                digits.append((char) ('0' + digit));
            } else if (character == '+' && i == 0) {
                international = true;
            } else if (character != ' ' && character != '-' && character != '.'
                    && character != '(' && character != ')') {
                // Extensions, pauses, and other syntax must not produce a false match.
                return Optional.empty();
            }
        }
        if (!international && digits.length() > 2 && digits.substring(0, 2).equals("00")) {
            international = true;
            digits.delete(0, 2);
        }
        if (digits.length() < 7 || digits.length() > 15) {
            return Optional.empty();
        }
        return Optional.of((international ? "+" : "") + digits);
    }

    /**
     * Extracts a possible base number from extension syntax for review only;
     * it must never be used to establish an automatic contact match.
     */
    public static Optional<String> uncertainBase(String number) {
        Objects.requireNonNull(number, "number");
        String input = number.trim();
        java.util.regex.Matcher marker = java.util.regex.Pattern.compile(
                "(?i)(?:ext\\.?|x|[;,#])").matcher(input);
        if (!marker.find()) {
            return Optional.empty();
        }
        return key(input.substring(0, marker.start()));
    }
}
