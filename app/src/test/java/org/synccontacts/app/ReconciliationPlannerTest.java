package org.synccontacts.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exercises reconciliation planning using synthetic contact snapshots. */
public final class ReconciliationPlannerTest {
    private static final String PHONE = "+49 (30) 12345678";
    private static final ContactComparison.Value ALICE = value("Alice", "name");
    private static final ContactComparison.Value BOB = value("Bob", "name");
    private static final ContactComparison.Value MAIL = value("alice@example.test", "work");
    private static final Map<String, ContactComparison.Cardinality> FIELDS = Map.of(
            "name", ContactComparison.Cardinality.SINGLE,
            "email", ContactComparison.Cardinality.MULTIPLE);

    /** Creates a normalized test field while preserving meaningful type metadata. */
    private static ContactComparison.Value value(String text, String type) {
        return new ContactComparison.Value(text, type, "");
    }

    /** Creates a synthetic contact with one phone and an optional email. */
    private static ReconciliationPlanner.Contact contact(String id, String phone,
            ContactComparison.Value name, ContactComparison.Value email) {
        Map<String, Set<ContactComparison.Value>> fields = email == null
                ? Map.of("name", Set.of(name))
                : Map.of("name", Set.of(name), "email", Set.of(email));
        return new ReconciliationPlanner.Contact(id,
                phone == null ? Set.of() : Set.of(phone), fields);
    }

    /** Runs the planner over a single synthetic source and destination. */
    private static ReconciliationPlanner.Plan single(
            ReconciliationPlanner.Contact source, ReconciliationPlanner.Contact destination,
            List<ReconciliationPlanner.Link> links) {
        return ReconciliationPlanner.plan(List.of(source), List.of(destination), links, FIELDS).get(0);
    }

    /** Initial equal contacts match without requesting destination changes. */
    @Test
    public void equalContactsRequireNoChanges() {
        var plan = single(contact("a", PHONE, ALICE, null),
                contact("b", "0049 30 12345678", ALICE, null), List.of());
        assertEquals(ReconciliationPlanner.Action.RECONCILE, plan.action());
        assertEquals("b", plan.destinationId());
        assertTrue(plan.comparison().additions().isEmpty());
        assertTrue(plan.comparison().conflicts().isEmpty());
    }

    /** Source-only values are additive and destination-only values are preserved. */
    @Test
    public void plansSubsetRulesWithoutDestinationRemoval() {
        var rich = contact("a", PHONE, ALICE, MAIL);
        var plain = contact("b", PHONE, ALICE, null);
        assertEquals(Map.of("email", Set.of(MAIL)), single(rich, plain, List.of())
                .comparison().additions());
        assertTrue(single(contact("a", PHONE, ALICE, null),
                contact("b", PHONE, ALICE, MAIL), List.of()).comparison().additions().isEmpty());
    }

    /** Conflicting single-value fields require review instead of an overwrite. */
    @Test
    public void plansConflictSeparatelyFromSafeAddition() {
        var plan = single(contact("a", PHONE, ALICE, MAIL),
                contact("b", PHONE, BOB, null), List.of());
        assertEquals(Map.of("email", Set.of(MAIL)), plan.comparison().additions());
        assertEquals("name", plan.comparison().conflicts().get(0).field());
        assertEquals(Set.of(BOB), plan.comparison().conflicts().get(0).destination());
    }

    /** Unmatched and phoneless contacts have create plans, not implicit writes. */
    @Test
    public void unmatchedContactsArePlannedForCreation() {
        var destination = contact("b", "+1 202 555 0140", ALICE, null);
        for (String phone : List.of("+49 30 12345678", "")) {
            var plan = single(contact("a", phone, ALICE, null), destination, List.of());
            assertEquals(ReconciliationPlanner.Action.CREATE, plan.action());
            assertNull(plan.destinationId());
        }
    }

    /** Multiple destinations sharing a phone always require an individual choice. */
    @Test
    public void ambiguousDestinationRequiresReview() {
        var result = ReconciliationPlanner.plan(
                List.of(contact("a", PHONE, ALICE, null)),
                List.of(contact("b1", PHONE, ALICE, null),
                        contact("b2", PHONE, ALICE, null)), List.of(), FIELDS);
        assertEquals(ReconciliationPlanner.Action.REVIEW_MATCH, result.get(0).action());
        assertEquals(Set.of("b1", "b2"), result.get(0).candidates());
    }

    /** Two unlinked sources may not automatically claim the same destination. */
    @Test
    public void competingSourcesRequireReview() {
        var result = ReconciliationPlanner.plan(
                List.of(contact("a1", PHONE, ALICE, null), contact("a2", PHONE, ALICE, null)),
                List.of(contact("b", PHONE, ALICE, null)), List.of(), FIELDS);
        assertEquals(ReconciliationPlanner.Action.REVIEW_MATCH, result.get(0).action());
        assertEquals(ReconciliationPlanner.Action.REVIEW_MATCH, result.get(1).action());
    }

    /** A validated link survives phone changes and reserves its destination. */
    @Test
    public void preservesLinksWhenNumbersChangeAndOtherSourcesCompete() {
        var result = ReconciliationPlanner.plan(
                List.of(contact("a1", "+1 202 555 0140", ALICE, null),
                        contact("a2", PHONE, ALICE, null)),
                List.of(contact("b", PHONE, ALICE, null)),
                List.of(new ReconciliationPlanner.Link("a1", "b")), FIELDS);
        assertEquals(ReconciliationPlanner.Action.RECONCILE, result.get(0).action());
        assertEquals(ReconciliationPlanner.Action.REVIEW_MATCH, result.get(1).action());
    }

    /** A missing linked destination is not silently treated as a new contact. */
    @Test
    public void missingLinkedDestinationNeedsReview() {
        var plan = single(contact("a", PHONE, ALICE, null),
                contact("other", PHONE, ALICE, null),
                List.of(new ReconciliationPlanner.Link("a", "missing")));
        assertEquals(ReconciliationPlanner.Action.REVIEW_LINK, plan.action());
    }

    /** Extension syntax cannot silently become an automatic match or duplicate copy. */
    @Test
    public void extensionRequiresReview() {
        var sourceExtension = single(contact("a", PHONE + " x12", ALICE, null),
                contact("b", PHONE, ALICE, null), List.of());
        assertEquals(ReconciliationPlanner.Action.REVIEW_MATCH, sourceExtension.action());
        assertEquals(Set.of("b"), sourceExtension.candidates());
        var destinationExtension = single(contact("a", PHONE, ALICE, null),
                contact("b", PHONE + " ext. 12", ALICE, null), List.of());
        assertEquals(ReconciliationPlanner.Action.REVIEW_MATCH, destinationExtension.action());
        assertEquals(Set.of("b"), destinationExtension.candidates());
        var noMatch = single(contact("a", PHONE + ";12", ALICE, null),
                contact("b", "+1 202 555 0140", ALICE, null), List.of());
        assertEquals(ReconciliationPlanner.Action.REVIEW_MATCH, noMatch.action());
    }

    /** Duplicate link and contact identifiers are rejected rather than overwritten. */
    @Test
    public void rejectsDuplicateIdentifiers() {
        var source = contact("a", PHONE, ALICE, null);
        assertThrows(IllegalArgumentException.class, () -> ReconciliationPlanner.plan(
                List.of(source, source), List.of(), List.of(), FIELDS));
        assertThrows(IllegalArgumentException.class, () -> ReconciliationPlanner.plan(
                List.of(source), List.of(), List.of(new ReconciliationPlanner.Link("a", "b"),
                        new ReconciliationPlanner.Link("a", "c")), FIELDS));
    }

    /** Unsupported data is never silently presented as synchronized. */
    @Test
    public void rejectsUnclassifiedFields() {
        var unknown = new ReconciliationPlanner.Contact("a", Set.of(PHONE),
                Map.of("custom", Set.of(ALICE)));
        assertThrows(IllegalArgumentException.class, () ->
                single(unknown, contact("b", PHONE, ALICE, null), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                ReconciliationPlanner.plan(List.of(unknown), List.of(), List.of(), FIELDS));
    }
}
