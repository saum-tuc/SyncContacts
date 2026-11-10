package org.synccontacts.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Stores pair-scoped contact links and unresolved user decisions durably. */
public final class SyncStateStore extends SQLiteOpenHelper {
    private static final String DATABASE = "sync-state.db";

    /** Opens the durable, app-private synchronization state database. */
    public SyncStateStore(Context context) {
        super(context, DATABASE, null, 1);
    }

    /** Creates pair-scoped links and conflict records. */
    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE links ("
                + "pair_key TEXT NOT NULL, source_id TEXT NOT NULL, destination_id TEXT NOT NULL, "
                + "PRIMARY KEY(pair_key, source_id), UNIQUE(pair_key, destination_id))");
        database.execSQL("CREATE TABLE conflicts ("
                + "conflict_key TEXT PRIMARY KEY NOT NULL, pair_key TEXT NOT NULL, "
                + "source_id TEXT NOT NULL, destination_id TEXT NOT NULL, field TEXT NOT NULL, "
                + "source_version INTEGER NOT NULL, destination_version INTEGER NOT NULL, "
                + "source_values TEXT NOT NULL, destination_values TEXT NOT NULL)");
        database.execSQL("CREATE TABLE matches ("
                + "pair_key TEXT NOT NULL, source_id TEXT NOT NULL, source_version INTEGER NOT NULL, "
                + "candidates TEXT NOT NULL, PRIMARY KEY(pair_key, source_id))");
        database.execSQL("CREATE TABLE copy_overrides ("
                + "pair_key TEXT NOT NULL, source_id TEXT NOT NULL, "
                + "PRIMARY KEY(pair_key, source_id))");
    }

    /** Refuses unknown schema migrations instead of losing durable identity state. */
    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported synchronization-state upgrade");
    }

    /** Hashes both selected account identities to isolate state by direction and pair. */
    public static String pairKey(AddressBook source, AddressBook destination) throws JSONException {
        String input = source.encode() + "\n" + destination.encode();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    /** Loads all source-to-destination identity links for one active pair. */
    public List<ReconciliationPlanner.Link> links(String pairKey) {
        List<ReconciliationPlanner.Link> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("links",
                new String[]{"source_id", "destination_id"}, "pair_key = ?",
                new String[]{pairKey}, null, null, "source_id")) {
            while (cursor.moveToNext()) {
                result.add(new ReconciliationPlanner.Link(cursor.getString(0), cursor.getString(1)));
            }
        }
        return List.copyOf(result);
    }

    /** Commits a unique validated link after recording the link event. */
    public void link(String pairKey, String sourceId, String destinationId) {
        ContentValues values = new ContentValues();
        values.put("pair_key", pairKey);
        values.put("source_id", sourceId);
        values.put("destination_id", destinationId);
        getWritableDatabase().insertOrThrow("links", null, values);
    }

    /** Removes a link only after its destination deletion has succeeded. */
    public void unlink(String pairKey, String sourceId, String destinationId) {
        int removed = getWritableDatabase().delete("links",
                "pair_key = ? AND source_id = ? AND destination_id = ?",
                new String[]{pairKey, sourceId, destinationId});
        if (removed != 1) {
            throw new IllegalStateException("Validated contact link disappeared");
        }
    }

    /** Replaces a pending conflict with the current observed field versions. */
    public void putConflict(SyncConflict conflict) {
        if (conflict == null) {
            throw new IllegalArgumentException("Conflict is required");
        }
        ContentValues values = new ContentValues();
        values.put("conflict_key", conflict.key());
        values.put("pair_key", conflict.pairKey());
        values.put("source_id", conflict.sourceId());
        values.put("destination_id", conflict.destinationId());
        values.put("field", conflict.field());
        values.put("source_version", conflict.sourceVersion());
        values.put("destination_version", conflict.destinationVersion());
        values.put("source_values", conflict.sourceValues());
        values.put("destination_values", conflict.destinationValues());
        getWritableDatabase().insertWithOnConflict("conflicts", null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Lists pending conflicts for the currently selected pair. */
    public List<SyncConflict> conflicts(String pairKey) {
        List<SyncConflict> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("conflicts",
                new String[]{"pair_key", "source_id", "destination_id", "field",
                        "source_version", "destination_version", "source_values",
                        "destination_values"},
                "pair_key = ?", new String[]{pairKey}, null, null, "source_id, field")) {
            while (cursor.moveToNext()) {
                result.add(new SyncConflict(cursor.getString(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3), cursor.getLong(4),
                        cursor.getLong(5), cursor.getString(6), cursor.getString(7)));
            }
        }
        return List.copyOf(result);
    }

    /** Removes a pending decision after it has been resolved or found stale. */
    public void resolveConflict(String key) {
        int removed = getWritableDatabase().delete("conflicts", "conflict_key = ?",
                new String[]{key});
        if (removed != 1) {
            throw new IllegalStateException("Pending conflict was already resolved");
        }
    }

    /** Refreshes one ambiguous match prompt with the current source version. */
    public void putMatch(ReconciliationPlanner.MatchDecision match, String pairKey) {
        ContentValues values = new ContentValues();
        values.put("pair_key", pairKey);
        values.put("source_id", match.sourceId());
        values.put("source_version", match.sourceVersion());
        values.put("candidates", new JSONArray(match.candidates()).toString());
        getWritableDatabase().insertWithOnConflict("matches", null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Loads unresolved phone-match choices for the selected pair. */
    public List<ReconciliationPlanner.MatchDecision> matches(String pairKey) {
        List<ReconciliationPlanner.MatchDecision> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("matches",
                new String[]{"source_id", "source_version", "candidates"}, "pair_key = ?",
                new String[]{pairKey}, null, null, "source_id")) {
            while (cursor.moveToNext()) {
                try {
                    JSONArray array = new JSONArray(cursor.getString(2));
                    List<String> candidates = new ArrayList<>();
                    for (int i = 0; i < array.length(); i++) {
                        candidates.add(array.getString(i));
                    }
                    result.add(new ReconciliationPlanner.MatchDecision(cursor.getString(0),
                            cursor.getLong(1), Set.copyOf(candidates)));
                } catch (JSONException error) {
                    throw new IllegalStateException("Invalid stored match decision", error);
                }
            }
        }
        return List.copyOf(result);
    }

    /** Removes the current match prompt after a selected resolution. */
    public void resolveMatch(String pairKey, String sourceId) {
        getWritableDatabase().delete("matches", "pair_key = ? AND source_id = ?",
                new String[]{pairKey, sourceId});
    }

    /** Marks a user-approved copy-as-new choice for future planning. */
    public void forceCreate(String pairKey, String sourceId) {
        ContentValues values = new ContentValues();
        values.put("pair_key", pairKey);
        values.put("source_id", sourceId);
        getWritableDatabase().insertWithOnConflict("copy_overrides", null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Returns source identifiers the user explicitly chose to copy as new. */
    public Set<String> forceCreates(String pairKey) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        try (Cursor cursor = getReadableDatabase().query("copy_overrides",
                new String[]{"source_id"}, "pair_key = ?", new String[]{pairKey},
                null, null, "source_id")) {
            while (cursor.moveToNext()) {
                result.add(cursor.getString(0));
            }
        }
        return Set.copyOf(result);
    }

    /** Removes a copy override after an explicit source-to-destination link exists. */
    public void clearForceCreate(String pairKey, String sourceId) {
        getWritableDatabase().delete("copy_overrides", "pair_key = ? AND source_id = ?",
                new String[]{pairKey, sourceId});
    }
}
