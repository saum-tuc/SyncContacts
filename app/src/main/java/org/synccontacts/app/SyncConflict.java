package org.synccontacts.app;

/** Identifies one source/destination field conflict requiring an explicit choice. */
public record SyncConflict(
        String pairKey, String sourceId, String destinationId, String field,
        long sourceVersion, long destinationVersion, String sourceValues,
        String destinationValues) {
    /** Produces a stable key for updating or resolving this pending decision. */
    public String key() {
        return pairKey + ":" + sourceId + ":" + destinationId + ":" + field;
    }
}
