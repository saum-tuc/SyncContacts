package org.synccontacts.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Produces non-destructive reconciliation plans from verified contact snapshots.
 * The caller is responsible for validating provider identities and account access.
 */
public final class ReconciliationPlanner {
    private ReconciliationPlanner() {
    }

    /** A single account-scoped contact and its normalized, supported details. */
    public record Contact(
            String id, Set<String> phoneNumbers,
            Map<String, Set<ContactComparison.Value>> fields) {
        public Contact {
            Objects.requireNonNull(id, "id");
            phoneNumbers = Set.copyOf(phoneNumbers);
            Map<String, Set<ContactComparison.Value>> copy = new LinkedHashMap<>();
            fields.forEach((field, values) -> copy.put(field, Set.copyOf(values)));
            fields = Collections.unmodifiableMap(copy);
        }
    }

    /** A previously validated source-to-destination identity link. */
    public record Link(String sourceId, String destinationId) {
        public Link {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(destinationId, "destinationId");
        }
    }

    /** Describes what the caller may consider doing, never a performed write. */
    public enum Action {
        CREATE,
        RECONCILE,
        REVIEW_MATCH,
        REVIEW_LINK
    }

    /** Carries an intended action and any field additions and conflicts. */
    public record Plan(
            String sourceId, Action action, String destinationId,
            Set<String> candidates, ContactComparison.Result comparison, String reason) {
        public Plan {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(action, "action");
            candidates = Set.copyOf(candidates);
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** A possible match retained for an explicit source-level decision. */
    public record MatchDecision(String sourceId, long sourceVersion, Set<String> candidates) {
        public MatchDecision {
            candidates = Set.copyOf(candidates);
        }
    }

    /** Separates possible matches involving unparsed extensions from safe matches. */
    private record Matches(Set<String> candidates, boolean needsReview) {
    }

    /**
     * Plans source additions and updates while rejecting ambiguous shared matches.
     * Deletions require separate provider identity and complete-scan checks.
     */
    public static List<Plan> plan(
            List<Contact> sources, List<Contact> destinations, List<Link> links,
            Map<String, ContactComparison.Cardinality> fields) {
        return plan(sources, destinations, links, Set.of(), fields);
    }

    /** Plans contacts while honoring an explicit user choice to copy a source as new. */
    public static List<Plan> plan(
            List<Contact> sources, List<Contact> destinations, List<Link> links,
            Set<String> forceCreate, Map<String, ContactComparison.Cardinality> fields) {
        index(sources);
        Map<String, Contact> destinationById = index(destinations);
        for (Contact contact : sources) {
            ContactComparison.compare(contact.fields(), Map.of(), fields);
        }
        for (Contact contact : destinations) {
            ContactComparison.compare(contact.fields(), Map.of(), fields);
        }
        Map<String, Link> linksBySource = new HashMap<>();
        Map<String, Set<String>> claims = new HashMap<>();
        Map<String, Set<String>> linkedClaims = new HashMap<>();

        // Existing links reserve their destination before new matches are considered.
        for (Link link : links) {
            if (linksBySource.put(link.sourceId(), link) != null) {
                throw new IllegalArgumentException("Duplicate source link: " + link.sourceId());
            }
            claims.computeIfAbsent(link.destinationId(), ignored -> new HashSet<>())
                    .add(link.sourceId());
            linkedClaims.computeIfAbsent(link.destinationId(), ignored -> new HashSet<>())
                    .add(link.sourceId());
        }
        Map<String, Matches> matches = new LinkedHashMap<>();
        for (Contact source : sources) {
            if (linksBySource.containsKey(source.id())) {
                continue;
            }
            if (forceCreate.contains(source.id())) {
                matches.put(source.id(), new Matches(Set.of(), false));
                continue;
            }
            Matches found = matchingDestinations(source, destinations);
            matches.put(source.id(), found);
            for (String id : found.candidates()) {
                claims.computeIfAbsent(id, ignored -> new HashSet<>()).add(source.id());
            }
        }

        // Plan every source independently; no destination is claimed by two sources.
        List<Plan> result = new ArrayList<>();
        for (Contact source : sources) {
            Link link = linksBySource.get(source.id());
            if (link != null) {
                result.add(planLinked(source, link, destinationById, linkedClaims, fields));
            } else {
                result.add(planUnlinked(source, matches.get(source.id()), destinationById,
                        claims, fields));
            }
        }
        return List.copyOf(result);
    }

    /** Validates unique identifiers before using them to build plans. */
    private static Map<String, Contact> index(List<Contact> contacts) {
        Map<String, Contact> result = new LinkedHashMap<>();
        for (Contact contact : contacts) {
            if (result.put(contact.id(), contact) != null) {
                throw new IllegalArgumentException("Duplicate contact id: " + contact.id());
            }
        }
        return result;
    }

    /** Returns destinations that share at least one safely normalized number. */
    private static Matches matchingDestinations(Contact source, List<Contact> destinations) {
        Set<String> keys = phoneKeys(source);
        Set<String> uncertainSource = uncertainPhoneKeys(source);
        Set<String> allSource = new HashSet<>(keys);
        allSource.addAll(uncertainSource);
        Set<String> result = new LinkedHashSet<>();
        boolean needsReview = !uncertainSource.isEmpty();
        for (Contact destination : destinations) {
            Set<String> destinationKeys = phoneKeys(destination);
            if (!Collections.disjoint(keys, destinationKeys)) {
                result.add(destination.id());
            }
            if (!Collections.disjoint(uncertainSource, destinationKeys)
                    || !Collections.disjoint(allSource, uncertainPhoneKeys(destination))) {
                result.add(destination.id());
                needsReview = true;
            }
        }
        return new Matches(result, needsReview);
    }

    /** Ignores unparseable numbers rather than using suffix or extension guesses. */
    private static Set<String> phoneKeys(Contact contact) {
        Set<String> keys = new HashSet<>();
        for (String phone : contact.phoneNumbers()) {
            PhoneNumbers.key(phone).ifPresent(keys::add);
        }
        return keys;
    }

    /** Returns possible base numbers that require explicit user review. */
    private static Set<String> uncertainPhoneKeys(Contact contact) {
        Set<String> keys = new HashSet<>();
        for (String phone : contact.phoneNumbers()) {
            PhoneNumbers.uncertainBase(phone).ifPresent(keys::add);
        }
        return keys;
    }

    /** Never reassigns an existing link when its destination cannot be verified. */
    private static Plan planLinked(Contact source, Link link,
            Map<String, Contact> destinations, Map<String, Set<String>> claims,
            Map<String, ContactComparison.Cardinality> fields) {
        Contact destination = destinations.get(link.destinationId());
        if (destination == null || claims.get(link.destinationId()).size() != 1) {
            return new Plan(source.id(), Action.REVIEW_LINK, link.destinationId(),
                    Set.of(), null, "Linked destination is missing or claimed by another source");
        }
        return reconcile(source, destination, fields);
    }

    /** Requests review instead of guessing when a phone match is shared. */
    private static Plan planUnlinked(Contact source, Matches matchResult,
            Map<String, Contact> destinations, Map<String, Set<String>> claims,
            Map<String, ContactComparison.Cardinality> fields) {
        Set<String> matches = matchResult.candidates();
        if (matchResult.needsReview()) {
            return new Plan(source.id(), Action.REVIEW_MATCH, null, matches, null,
                    "A phone extension or dialing suffix requires review");
        }
        if (matches.isEmpty()) {
            return new Plan(source.id(), Action.CREATE, null, Set.of(), null,
                    "No destination shares a usable phone number");
        }
        if (matches.size() != 1) {
            return new Plan(source.id(), Action.REVIEW_MATCH, null, matches, null,
                    "Multiple destination contacts match");
        }
        String destinationId = matches.iterator().next();
        if (claims.get(destinationId).size() != 1) {
            return new Plan(source.id(), Action.REVIEW_MATCH, null, matches, null,
                    "Destination contact is claimed by multiple source contacts");
        }
        return reconcile(source, destinations.get(destinationId), fields);
    }

    /** Computes additive changes and explicit single-value conflicts. */
    private static Plan reconcile(Contact source, Contact destination,
            Map<String, ContactComparison.Cardinality> fields) {
        ContactComparison.Result comparison =
                ContactComparison.compare(source.fields(), destination.fields(), fields);
        return new Plan(source.id(), Action.RECONCILE, destination.id(),
                Set.of(destination.id()), comparison, "Matched contact");
    }
}
