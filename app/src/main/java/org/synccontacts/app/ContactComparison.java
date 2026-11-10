package org.synccontacts.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compares already-normalized contact fields without modifying either contact.
 * The caller must provide only supported fields and classify their cardinality.
 */
public final class ContactComparison {
    private ContactComparison() {
    }

    /** Distinguishes fields that can safely have multiple values. */
    public enum Cardinality {
        SINGLE,
        MULTIPLE
    }

    /** Keeps a field's normalized value and meaningful type or label together. */
    public record Value(String value, String type, String label) {
        public Value {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(label, "label");
        }
    }

    /** Records a field requiring explicit user review. */
    public record Conflict(String field, Set<Value> source, Set<Value> destination) {
        public Conflict {
            Objects.requireNonNull(field, "field");
            source = Set.copyOf(source);
            destination = Set.copyOf(destination);
        }
    }

    /** Contains additions and conflicts; destination-only values are never removed. */
    public record Result(Map<String, Set<Value>> additions, List<Conflict> conflicts) {
        public Result {
            additions = Map.copyOf(additions);
            conflicts = List.copyOf(conflicts);
        }
    }

    /**
     * Plans additions and flags incompatible single-valued fields for review.
     * An absent field is equivalent to an empty set.
     */
    public static Result compare(
            Map<String, Set<Value>> source,
            Map<String, Set<Value>> destination,
            Map<String, Cardinality> supportedFields) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(supportedFields, "supportedFields");
        validateFields(source, supportedFields);
        validateFields(destination, supportedFields);

        Map<String, Set<Value>> additions = new java.util.LinkedHashMap<>();
        List<Conflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, Cardinality> entry : supportedFields.entrySet()) {
            String field = entry.getKey();
            Cardinality cardinality = Objects.requireNonNull(entry.getValue(), "cardinality");
            Set<Value> from = source.getOrDefault(field, Collections.emptySet());
            Set<Value> to = destination.getOrDefault(field, Collections.emptySet());
            validateValues(field, cardinality, from, to);
            Set<Value> missing = new LinkedHashSet<>(from);
            missing.removeAll(to);

            // Existing but different singleton values need a decision, not an overwrite.
            if (cardinality == Cardinality.SINGLE && !from.isEmpty()
                    && !to.isEmpty() && !missing.isEmpty()) {
                conflicts.add(new Conflict(field, from, to));
            } else if (!missing.isEmpty()) {
                additions.put(field, Set.copyOf(missing));
            }
        }
        return new Result(additions, conflicts);
    }

    /** Rejects unsupported fields rather than silently reporting them as synchronized. */
    private static void validateFields(
            Map<String, Set<Value>> fields, Map<String, Cardinality> supportedFields) {
        for (String field : fields.keySet()) {
            if (!supportedFields.containsKey(field)) {
                throw new IllegalArgumentException("Unsupported field: " + field);
            }
        }
    }

    /** Rejects malformed or ambiguous singleton values rather than guessing. */
    private static void validateValues(
            String field, Cardinality cardinality, Set<Value> from, Set<Value> to) {
        Objects.requireNonNull(from, "source values");
        Objects.requireNonNull(to, "destination values");
        for (Value value : from) {
            Objects.requireNonNull(value, "source value for " + field);
        }
        for (Value value : to) {
            Objects.requireNonNull(value, "destination value for " + field);
        }
        if (cardinality == Cardinality.SINGLE && (from.size() > 1 || to.size() > 1)) {
            throw new IllegalArgumentException("Multiple values for singleton field: " + field);
        }
    }
}
