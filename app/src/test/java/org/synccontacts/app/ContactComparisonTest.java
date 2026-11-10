package org.synccontacts.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;
import java.util.Set;

/** Tests the non-destructive comparison rules independently of Android. */
public final class ContactComparisonTest {
    private static final ContactComparison.Value ALICE =
            new ContactComparison.Value("Alice", "name", "");
    private static final ContactComparison.Value BOB =
            new ContactComparison.Value("Bob", "name", "");
    private static final ContactComparison.Value PHONE =
            new ContactComparison.Value("+49123456789", "mobile", "");
    private static final ContactComparison.Value EMAIL =
            new ContactComparison.Value("alice@example.test", "work", "");
    private static final Map<String, ContactComparison.Cardinality> FIELDS = Map.of(
            "name", ContactComparison.Cardinality.SINGLE,
            "phone", ContactComparison.Cardinality.MULTIPLE,
            "email", ContactComparison.Cardinality.MULTIPLE);

    /** Equal fields and destination-only values cause no writes. */
    @Test
    public void preservesDestinationOnlyValues() {
        var source = Map.of("name", Set.of(ALICE));
        var destination = Map.of("name", Set.of(ALICE), "email", Set.of(EMAIL));
        var result = ContactComparison.compare(source, destination, FIELDS);
        assertTrue(result.additions().isEmpty());
        assertTrue(result.conflicts().isEmpty());
    }

    /** Missing fields and additional multivalues are added without duplicates. */
    @Test
    public void addsOnlyMissingValues() {
        var source = Map.of("name", Set.of(ALICE), "phone", Set.of(PHONE),
                "email", Set.of(EMAIL));
        var destination = Map.of("name", Set.of(ALICE), "phone", Set.of(PHONE));
        var result = ContactComparison.compare(source, destination, FIELDS);
        assertEquals(Map.of("email", Set.of(EMAIL)), result.additions());
        assertTrue(result.conflicts().isEmpty());
    }

    /** Conflicts remain separate from unrelated safe additions. */
    @Test
    public void flagsConflictingSingletonWithoutOverwrite() {
        var result = ContactComparison.compare(
                Map.of("name", Set.of(ALICE), "phone", Set.of(PHONE)),
                Map.of("name", Set.of(BOB)), FIELDS);
        assertEquals(Map.of("phone", Set.of(PHONE)), result.additions());
        assertEquals(1, result.conflicts().size());
        assertEquals("name", result.conflicts().get(0).field());
        assertEquals(Set.of(ALICE), result.conflicts().get(0).source());
        assertEquals(Set.of(BOB), result.conflicts().get(0).destination());
    }

    /** Unclassified and malformed data cannot be silently ignored. */
    @Test
    public void rejectsUnknownAndInvalidFields() {
        assertThrows(IllegalArgumentException.class, () ->
                ContactComparison.compare(Map.of("custom", Set.of(ALICE)), Map.of(), FIELDS));
        assertThrows(IllegalArgumentException.class, () ->
                ContactComparison.compare(Map.of("name", Set.of(ALICE, BOB)), Map.of(), FIELDS));
    }

    /** Type and label are part of value identity. */
    @Test
    public void keepsDifferentLabelsDistinct() {
        var home = new ContactComparison.Value("+49123456789", "home", "");
        var result = ContactComparison.compare(
                Map.of("phone", Set.of(PHONE)), Map.of("phone", Set.of(home)), FIELDS);
        assertEquals(Map.of("phone", Set.of(PHONE)), result.additions());
    }

    /** A removed source value remains in the destination and is not a conflict. */
    @Test
    public void preservesRemovedSourceValue() {
        var result = ContactComparison.compare(
                Map.of(), Map.of("phone", Set.of(PHONE)), FIELDS);
        assertTrue(result.additions().isEmpty());
        assertTrue(result.conflicts().isEmpty());
    }

    /** The comparison never mutates the caller's contact snapshots. */
    @Test
    public void leavesInputsUnchanged() {
        Set<ContactComparison.Value> sourceValues = new java.util.HashSet<>(Set.of(PHONE));
        Set<ContactComparison.Value> destinationValues = new java.util.HashSet<>();
        var source = Map.of("phone", sourceValues);
        var destination = Map.of("phone", destinationValues);
        var result = ContactComparison.compare(source, destination, FIELDS);
        assertEquals(Set.of(PHONE), sourceValues);
        assertTrue(destinationValues.isEmpty());
        sourceValues.clear();
        assertEquals(Set.of(PHONE), result.additions().get("phone"));
    }
}
