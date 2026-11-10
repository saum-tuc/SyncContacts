package org.synccontacts.app;

import android.content.ContentValues;
import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coordinates stable account-scoped scans, explicit identity links, additive writes,
 * conflict prompts, and deletions guarded by the Contacts Provider transaction.
 */
public final class SyncEngine {
    private static final Object LOCK = new Object();

    /** Reports the result and contact names changed by one user-requested run. */
    public record RunReport(androidx.work.ListenableWorker.Result result, boolean success,
            List<String> syncedItems, String message) {
    }

    private SyncEngine() {
    }

    /** Runs one serialized check, returning success only when its work is durable. */
    public static androidx.work.ListenableWorker.Result run(Context context,
            java.util.function.BooleanSupplier stopped) {
        synchronized (LOCK) {
            return runLocked(context, stopped, true).result();
        }
    }

    /** Runs a one-off reconciliation regardless of the background-sync setting. */
    public static RunReport runManual(Context context,
            java.util.function.BooleanSupplier stopped) {
        synchronized (LOCK) {
            return runLocked(context, stopped, false);
        }
    }

    /** Applies or keeps one field conflict after revalidating both observed versions. */
    public static void resolveConflict(Context context, SyncConflict conflict, boolean useSource)
            throws IOException, JSONException, android.os.RemoteException,
            android.content.OperationApplicationException {
        synchronized (LOCK) {
            SyncSettings settings = new SyncSettings(context);
            AddressBook source = settings.source();
            AddressBook destination = settings.destination();
            if (source == null || destination == null
                    || !conflict.pairKey().equals(SyncStateStore.pairKey(source, destination))) {
                throw new IllegalStateException("Conflict belongs to an inactive address-book pair");
            }
            ProviderContact from = ProviderContact.read(context.getContentResolver(),
                    Long.parseLong(conflict.sourceId()), source);
            ProviderContact to = ProviderContact.read(context.getContentResolver(),
                    Long.parseLong(conflict.destinationId()), destination);
            if (!isConflictPending(context, conflict)) {
                throw new IllegalStateException("Conflict is no longer pending");
            }
            ContactComparison.Result current = ContactComparison.compare(
                    onlyField(from, conflict.field()), onlyField(to, conflict.field()),
                    Map.of(conflict.field(), ProviderContact.FIELD_TYPES.get(conflict.field())));
            if (from.identity().version() != conflict.sourceVersion()
                    || to.identity().version() != conflict.destinationVersion()) {
                try (SyncStateStore state = new SyncStateStore(context)) {
                    if (current.conflicts().isEmpty()) {
                        new ActivityLog(context).append("Conflict was resolved by a newer contact version: "
                                + conflict.sourceId() + " / " + conflict.field());
                        state.resolveConflict(conflict.key());
                    } else {
                        ContactComparison.Conflict refreshed = current.conflicts().get(0);
                        state.putConflict(new SyncConflict(conflict.pairKey(), conflict.sourceId(),
                                conflict.destinationId(), conflict.field(), from.identity().version(),
                                to.identity().version(), encodeValues(refreshed.source()),
                                encodeValues(refreshed.destination())));
                        new ActivityLog(context).append("Conflict changed before decision; refreshed for review: "
                                + conflict.sourceId() + " / " + conflict.field());
                    }
                }
                requestBackgroundIfEnabled(context, settings);
                return;
            }
            try (SyncStateStore state = new SyncStateStore(context)) {
                ActivityLog log = new ActivityLog(context);
                if (useSource) {
                    List<ContentValues> replacement = from.rows(conflict.field(),
                            from.fields().getOrDefault(conflict.field(), Set.of()));
                    new GuardedContactWriter(context).replaceField(conflict.key(),
                            from.identity(), to.identity(), conflict.field(), replacement);
                    log.append("User resolved conflict using source values for "
                            + conflict.sourceId() + " / " + conflict.field());
                } else {
                    log.append("User resolved conflict preserving destination values for "
                            + conflict.sourceId() + " / " + conflict.field());
                }
                state.resolveConflict(conflict.key());
            }
            requestBackgroundIfEnabled(context, settings);
        }
    }

    /** Confirms that the user decision is still present in durable pair state. */
    private static boolean isConflictPending(Context context, SyncConflict conflict) {
        try (SyncStateStore state = new SyncStateStore(context)) {
            return state.conflicts(conflict.pairKey()).contains(conflict);
        }
    }

    /** Projects a contact down to one field for stale-conflict revalidation. */
    private static Map<String, Set<ContactComparison.Value>> onlyField(
            ProviderContact contact, String field) {
        return contact.fields().containsKey(field)
                ? Map.of(field, contact.fields().get(field)) : Map.of();
    }

    /** Resolves a phone match by validating current source and destination identities. */
    public static void resolveMatch(Context context, String sourceId, String candidateId,
            boolean copyAsNew, long expectedSourceVersion) throws IOException, JSONException {
        synchronized (LOCK) {
            SyncSettings settings = new SyncSettings(context);
            AddressBook sourceBook = settings.source();
            AddressBook destinationBook = settings.destination();
            if (sourceBook == null || destinationBook == null) {
                throw new IllegalStateException("Synchronization is not configured");
            }
            String pair = SyncStateStore.pairKey(sourceBook, destinationBook);
            ProviderContact source = ProviderContact.read(context.getContentResolver(),
                    Long.parseLong(sourceId), sourceBook);
            if (source.identity().version() != expectedSourceVersion) {
                throw new IllegalStateException("Source contact changed; run a fresh check and review again");
            }
            try (SyncStateStore state = new SyncStateStore(context)) {
                boolean pending = state.matches(pair).stream()
                        .anyMatch(match -> match.sourceId().equals(sourceId)
                                && match.sourceVersion() == expectedSourceVersion
                                && (copyAsNew || match.candidates().contains(candidateId)));
                if (!pending || (!copyAsNew && candidateId == null)) {
                    throw new IllegalStateException("Phone-match decision is no longer pending");
                }
                ActivityLog log = new ActivityLog(context);
                if (copyAsNew) {
                    state.forceCreate(pair, sourceId);
                    log.append("User chose to copy unmatched source raw contact as new: " + sourceId);
                } else {
                    ProviderContact destination = ProviderContact.read(context.getContentResolver(),
                            Long.parseLong(candidateId), destinationBook);
                    state.link(pair, sourceId, candidateId);
                    log.append("User linked source raw contact " + sourceId
                            + " to destination raw contact " + candidateId);
                }
                state.resolveMatch(pair, sourceId);
            }
            requestBackgroundIfEnabled(context, settings);
        }
    }

    /** Loads the active pair and captures stable source and destination snapshots. */
    private static RunReport runLocked(Context context,
            java.util.function.BooleanSupplier stopped, boolean requireBackgroundSync) {
        SyncSettings settings = new SyncSettings(context);
        ActivityLog log = new ActivityLog(context);
        long generation = -1;
        Set<String> syncedItems = new LinkedHashSet<>();
        try {
            AddressBook source;
            AddressBook destination;
            synchronized (SyncSettings.LOCK) {
                if ((requireBackgroundSync && !settings.backgroundSyncEnabled())
                        || stopped.getAsBoolean()) {
                    return new RunReport(androidx.work.ListenableWorker.Result.success(), true,
                            List.of(), "Background synchronization is disabled.");
                }
                source = settings.source();
                destination = settings.destination();
                generation = settings.generation();
            }
            if (source == null || destination == null || source.equals(destination)) {
                throw new IllegalStateException("Select distinct source and destination address books");
            }
            if (!ContactBooks.isAvailable(context, source)) {
                throw new IllegalStateException("Source address book account is unavailable");
            }
            if (!ContactBooks.isAvailable(context, destination)) {
                throw new IllegalStateException("Destination address book account is unavailable");
            }

            ContactSnapshot reader = new ContactSnapshot(context.getContentResolver());
            AtomicFile snapshotFile = ContactSnapshot.file(context.getFilesDir(), source, destination);
            JSONObject previous = ContactSnapshot.load(snapshotFile);
            JSONObject current = reader.read(source);
            JSONObject destinationScan = reader.read(destination);
            if (!sameSnapshot(current, reader.read(source))
                    || !sameSnapshot(destinationScan, reader.read(destination))) {
                throw new IllegalStateException("Address book changed during scan; retrying");
            }
            Map<String, ProviderContact> sources = readContacts(
                    context, source, current, stopped);
            Map<String, ProviderContact> destinations = readContacts(
                    context, destination, destinationScan, stopped);
            String pairKey = SyncStateStore.pairKey(source, destination);
            try (SyncStateStore state = new SyncStateStore(context)) {
                List<String> events = changes(source, destination, previous, current);
                log.appendAll(events);
                List<ReconciliationPlanner.Plan> plans = plan(sources, destinations, state, pairKey);
                Set<String> seenConflicts = new HashSet<>();
                Set<String> seenMatches = new HashSet<>();
                int pending = 0;
                int applied = 0;
                for (ReconciliationPlanner.Plan plan : plans) {
                    if (stopped.getAsBoolean()
                            || !active(settings, generation, requireBackgroundSync)) {
                        return new RunReport(androidx.work.ListenableWorker.Result.success(), true,
                                List.copyOf(syncedItems), "Synchronization stopped.");
                    }
                    ProviderContact from = sources.get(plan.sourceId());
                    switch (plan.action()) {
                        case CREATE -> {
                            try {
                                long destinationId = create(context, log, pairKey,
                                        destination, from);
                                log.append("Linked source raw contact " + plan.sourceId()
                                        + " to new destination raw contact " + destinationId);
                                state.link(pairKey, plan.sourceId(), Long.toString(destinationId));
                                state.clearForceCreate(pairKey, plan.sourceId());
                                syncedItems.add(from.displayName());
                                applied++;
                            } catch (IllegalStateException unresolved) {
                                pending++;
                                log.append("Destination creation needs review for source raw contact "
                                        + plan.sourceId() + ": " + unresolved.getMessage());
                            }
                        }
                        case RECONCILE -> {
                            ProviderContact to = destinations.get(plan.destinationId());
                            linkIfNeeded(log, state, pairKey, plan, from, to);
                            int changesApplied = append(
                                    context, plan, from, to, source, destination, pairKey);
                            if (changesApplied > 0) {
                                syncedItems.add(from.displayName());
                            }
                            applied += changesApplied;
                            ProviderContact conflictDestination = ProviderContact.read(
                                    context.getContentResolver(), to.identity().rawId(), destination);
                            pending += recordConflicts(log, state, pairKey, plan, from, conflictDestination,
                                    seenConflicts);
                        }
                        case REVIEW_MATCH -> {
                            state.putMatch(new ReconciliationPlanner.MatchDecision(
                                    plan.sourceId(), from.identity().version(), plan.candidates()), pairKey);
                            seenMatches.add(plan.sourceId());
                            log.append("Phone match requires review for source raw contact "
                                    + plan.sourceId() + ": " + plan.candidates());
                            pending++;
                        }
                        case REVIEW_LINK -> {
                            log.append("Linked destination needs review for source raw contact "
                                    + plan.sourceId() + ": " + plan.reason());
                            pending++;
                        }
                    }
                    for (String mime : from.unsupported()) {
                        log.append("Unsupported source data type for raw contact "
                                + from.identity().rawId() + ": " + mime);
                    }
                }
                clearObsoleteConflicts(log, state, pairKey, seenConflicts);
                clearObsoleteMatches(log, state, pairKey, seenMatches);
                applied += deleteMissingSources(context, log, state, pairKey, source,
                        destination, previous, current, stopped, settings, generation,
                        requireBackgroundSync, syncedItems);
                if (!active(settings, generation, requireBackgroundSync) || stopped.getAsBoolean()) {
                    return new RunReport(androidx.work.ListenableWorker.Result.success(), true,
                            List.copyOf(syncedItems), "Synchronization stopped.");
                }

                // Commit the observation only after writes and pending decisions are durable.
                JSONObject finalSource = reader.read(source);
                if (!sameSnapshot(current, finalSource)) {
                    throw new IllegalStateException("Source changed during synchronization; retrying");
                }
                ContactSnapshot.save(snapshotFile, current);
                String status = pending == 0
                        ? "Sync check completed. " + applied + " destination action(s) applied."
                        : "Sync check completed with " + pending + " pending review item(s).";
                settings.status(status);
                log.append("Sync check completed: " + status);
                return new RunReport(androidx.work.ListenableWorker.Result.success(), true,
                        List.copyOf(syncedItems), status);
            }
        } catch (IOException | JSONException | IllegalStateException | IllegalArgumentException
                 | SecurityException | android.os.RemoteException
                 | android.content.OperationApplicationException error) {
            String message = "Sync check failed: " + error.getClass().getSimpleName()
                    + ": " + String.valueOf(error.getMessage());
            synchronized (SyncSettings.LOCK) {
                if (generation >= 0 && (settings.generation() != generation
                        || (requireBackgroundSync && !settings.backgroundSyncEnabled())
                        || stopped.getAsBoolean())) {
                    return new RunReport(androidx.work.ListenableWorker.Result.success(), true,
                            List.copyOf(syncedItems), "Synchronization stopped.");
                }
                try {
                    log.append(message);
                    settings.status(message);
                } catch (IOException | RuntimeException logError) {
                    android.util.Log.e("SyncContacts", "Unable to persist synchronization error", logError);
                }
            }
            return new RunReport(androidx.work.ListenableWorker.Result.retry(), false,
                    List.copyOf(syncedItems), message);
        }
    }

    /** Reads contacts only from the selected account and rejects partial scans. */
    private static Map<String, ProviderContact> readContacts(Context context, AddressBook book,
            JSONObject snapshot, java.util.function.BooleanSupplier stopped) throws IOException {
        Map<String, ProviderContact> result = new LinkedHashMap<>();
        Iterator<String> ids = snapshot.keys();
        while (ids.hasNext()) {
            if (stopped.getAsBoolean()) {
                throw new IOException("Synchronization was stopped");
            }
            String id = ids.next();
            long rawId = Long.parseLong(id);
            ProviderContact contact = ProviderContact.read(context.getContentResolver(), rawId, book);
            result.put(id, contact);
        }
        return result;
    }

    /** Applies persisted links and deliberate copy-as-new overrides before matching. */
    private static List<ReconciliationPlanner.Plan> plan(
            Map<String, ProviderContact> sources, Map<String, ProviderContact> destinations,
            SyncStateStore state, String pairKey) {
        List<ReconciliationPlanner.Contact> from = new ArrayList<>();
        sources.values().forEach(contact -> from.add(contact.plannerRecord()));
        List<ReconciliationPlanner.Contact> to = new ArrayList<>();
        destinations.values().forEach(contact -> to.add(contact.plannerRecord()));
        return ReconciliationPlanner.plan(from, to, state.links(pairKey),
                state.forceCreates(pairKey), ProviderContact.FIELD_TYPES);
    }

    /** Creates the destination only after a stable source version and durable intent. */
    private static long create(Context context, ActivityLog log, String pairKey,
            AddressBook destination, ProviderContact from)
            throws IOException, android.os.RemoteException,
            android.content.OperationApplicationException, JSONException {
        List<ContentValues> rows = allRows(from);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Contact has no supported data to copy");
        }
        log.append("Creating destination for source raw contact " + from.identity().rawId());
        return new GuardedContactWriter(context).create(
                operationId(pairKey, "create", from.identity().rawId(), 0, "all"),
                from.identity(), destination, rows);
    }

    /** Collects all supported source rows for a new destination raw contact. */
    private static List<ContentValues> allRows(ProviderContact contact) {
        List<ContentValues> result = new ArrayList<>();
        for (String mime : ProviderContact.FIELD_TYPES.keySet()) {
            result.addAll(contact.rows(mime, contact.fields().getOrDefault(mime, Set.of())));
        }
        return List.copyOf(result);
    }

    /** Persists a unique link before applying updates or resolving fields. */
    private static void linkIfNeeded(ActivityLog log, SyncStateStore state, String pairKey,
            ReconciliationPlanner.Plan plan, ProviderContact source, ProviderContact destination)
            throws IOException {
        for (ReconciliationPlanner.Link link : state.links(pairKey)) {
            if (link.sourceId().equals(plan.sourceId())
                    && link.destinationId().equals(plan.destinationId())) {
                return;
            }
        }
        log.append("Linked existing destination raw contact " + plan.destinationId()
                + " to source raw contact " + plan.sourceId());
        state.link(pairKey, Long.toString(source.identity().rawId()),
                Long.toString(destination.identity().rawId()));
    }

    /** Applies nonconflicting missing data while preserving destination-only rows. */
    private static int append(Context context, ReconciliationPlanner.Plan plan,
            ProviderContact from, ProviderContact to, AddressBook source, AddressBook destination,
            String pairKey) throws IOException, android.os.RemoteException,
            android.content.OperationApplicationException {
        int applied = 0;
        for (Map.Entry<String, Set<ContactComparison.Value>> entry
                : plan.comparison().additions().entrySet()) {
            List<ContentValues> rows = from.rows(entry.getKey(), entry.getValue());
            if (!rows.isEmpty()) {
                ProviderContact currentDestination = ProviderContact.read(
                        context.getContentResolver(), to.identity().rawId(), destination);
                String operation = operationId(pairKey, "append",
                        from.identity().rawId(), to.identity().rawId(), entry.getKey());
                new GuardedContactWriter(context).append(operation, from.identity(),
                        currentDestination.identity(), rows);
                applied += rows.size();
            }
        }
        return applied;
    }

    /** Stores each field conflict and its exact observed values for individual review. */
    private static int recordConflicts(ActivityLog log, SyncStateStore state, String pairKey,
            ReconciliationPlanner.Plan plan, ProviderContact from, ProviderContact to,
            Set<String> seen) throws IOException, JSONException {
        for (ContactComparison.Conflict conflict : plan.comparison().conflicts()) {
            String sourceValues = encodeValues(conflict.source());
            String destinationValues = encodeValues(conflict.destination());
            SyncConflict pending = new SyncConflict(pairKey, plan.sourceId(),
                    plan.destinationId(), conflict.field(), from.identity().version(),
                    to.identity().version(), sourceValues, destinationValues);
            state.putConflict(pending);
            seen.add(pending.key());
            log.append("Conflict requires individual review for source raw "
                    + plan.sourceId() + ", destination raw " + plan.destinationId()
                    + ", field " + conflict.field());
        }
        return plan.comparison().conflicts().size();
    }

    /** Serializes labels and values for review without losing field distinctions. */
    private static String encodeValues(Set<ContactComparison.Value> values) throws JSONException {
        JSONArray array = new JSONArray();
        for (ContactComparison.Value value : values) {
            JSONObject object = new JSONObject();
            object.put("value", value.value());
            object.put("type", value.type());
            object.put("label", value.label());
            array.put(object);
        }
        return array.toString();
    }

    /** Removes conflicts that changed or disappeared since the latest stable read. */
    private static void clearObsoleteConflicts(ActivityLog log, SyncStateStore state,
            String pairKey, Set<String> seen) throws IOException {
        for (SyncConflict conflict : state.conflicts(pairKey)) {
            if (!seen.contains(conflict.key())) {
                log.append("Pending field conflict became obsolete and was refreshed: "
                        + conflict.sourceId() + " / " + conflict.field());
                state.resolveConflict(conflict.key());
            }
        }
    }

    /** Removes match prompts that have become unique or disappeared since the last scan. */
    private static void clearObsoleteMatches(ActivityLog log, SyncStateStore state,
            String pairKey, Set<String> seen) throws IOException {
        for (ReconciliationPlanner.MatchDecision match : state.matches(pairKey)) {
            if (!seen.contains(match.sourceId())) {
                log.append("Pending phone-match decision is no longer needed: " + match.sourceId());
                state.resolveMatch(pairKey, match.sourceId());
            }
        }
    }

    /** Deletes only previously linked source contacts missing from a complete stable scan. */
    private static int deleteMissingSources(Context context, ActivityLog log, SyncStateStore state,
            String pairKey, AddressBook source, AddressBook destination, JSONObject previous,
            JSONObject current, java.util.function.BooleanSupplier stopped, SyncSettings settings,
            long generation, boolean requireBackgroundSync, Set<String> syncedItems)
            throws IOException, JSONException, android.os.RemoteException,
            android.content.OperationApplicationException {
        if (previous == null) {
            return 0;
        }
        Map<String, String> links = new HashMap<>();
        for (ReconciliationPlanner.Link link : state.links(pairKey)) {
            links.put(link.sourceId(), link.destinationId());
        }
        int deleted = 0;
        Iterator<String> ids = previous.keys();
        while (ids.hasNext()) {
            String sourceId = ids.next();
            if (current.has(sourceId) || !links.containsKey(sourceId)) {
                continue;
            }
            if (stopped.getAsBoolean()
                    || !active(settings, generation, requireBackgroundSync)) {
                return deleted;
            }
            long destinationId = Long.parseLong(links.get(sourceId));
            ProviderContact to = ProviderContact.read(
                    context.getContentResolver(), destinationId, destination);
            String operation = operationId(pairKey, "delete",
                    Long.parseLong(sourceId), destinationId, "whole-contact");
            long removed = new GuardedContactWriter(context).deleteLinked(
                    operation, Long.parseLong(sourceId), source, to.identity());
            syncedItems.add(to.displayName());
            log.append("Deleted destination raw contact " + removed
                    + " for deleted source raw contact " + sourceId);
            state.unlink(pairKey, sourceId, Long.toString(removed));
            deleted++;
        }
        return deleted;
    }

    /** Rejects stale pair settings or a disabled background run before further writes. */
    private static boolean active(SyncSettings settings, long generation,
            boolean requireBackgroundSync) {
        synchronized (SyncSettings.LOCK) {
            return settings.generation() == generation
                    && (!requireBackgroundSync || settings.backgroundSyncEnabled());
        }
    }

    /** Avoids queuing background work after user decisions when automation is disabled. */
    private static void requestBackgroundIfEnabled(Context context, SyncSettings settings) {
        if (settings.backgroundSyncEnabled()) {
            MonitorScheduler.request(context);
        }
    }

    /** Builds stable operation identifiers so retries reuse completed journal entries. */
    private static String operationId(String pair, String action, long sourceId,
            long destinationId, String field) {
        return pair + ":" + action + ":" + sourceId + ":" + destinationId + ":" + field;
    }

    /** Compares account snapshots without depending on JSON object key order. */
    private static boolean sameSnapshot(JSONObject first, JSONObject second) throws JSONException {
        if (first.length() != second.length()) {
            return false;
        }
        Iterator<String> ids = first.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            if (!second.has(id)
                    || !first.getJSONArray(id).toString().equals(second.getJSONArray(id).toString())) {
                return false;
            }
        }
        return true;
    }

    /** Records observed source additions, modifications, and deletions. */
    private static List<String> changes(AddressBook source, AddressBook destination,
            JSONObject previous, JSONObject current) throws JSONException {
        List<String> events = new ArrayList<>();
        String pair = "From " + source.label() + " to " + destination.label();
        events.add(pair + ": sync check started");
        Iterator<String> ids = current.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONArray now = current.getJSONArray(id);
            if (previous == null || !previous.has(id)) {
                events.add(pair + ": " + (previous == null ? "initial discovery" : "added")
                        + " source raw contact " + id + " details=" + now);
            } else if (!now.toString().equals(previous.getJSONArray(id).toString())) {
                events.add(pair + ": modified source raw contact " + id + " old="
                        + previous.getJSONArray(id) + " new=" + now);
            }
        }
        if (previous != null) {
            ids = previous.keys();
            while (ids.hasNext()) {
                String id = ids.next();
                if (!current.has(id)) {
                    events.add(pair + ": deleted source raw contact " + id
                            + " previous details=" + previous.getJSONArray(id));
                }
            }
        }
        return events;
    }
}
