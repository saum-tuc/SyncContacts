package org.synccontacts.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Persists write intents so an interrupted provider operation is never retried
 * as though it had definitely failed.
 */
public final class ContactWriteJournal extends SQLiteOpenHelper {
    /** Represents a committed write or an operation requiring investigation. */
    public record Entry(String fingerprint, String state, Long destinationId) {
    }

    /** Opens the private, durable operation journal. */
    public ContactWriteJournal(Context context) {
        super(context, "contact-write-journal.db", null, 1);
    }

    /** Creates the table once; operation identifiers are unique across pairs. */
    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE writes ("
                + "operation_id TEXT PRIMARY KEY NOT NULL, "
                + "fingerprint TEXT NOT NULL, state TEXT NOT NULL, destination_id INTEGER)");
    }

    /** Refuses an unknown schema upgrade instead of discarding pending work. */
    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported write journal upgrade");
    }

    /** Looks up a previously recorded intent. */
    public Entry find(String operationId) {
        try (Cursor cursor = getReadableDatabase().query("writes",
                new String[]{"fingerprint", "state", "destination_id"}, "operation_id = ?",
                new String[]{operationId}, null, null, null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return new Entry(cursor.getString(0), cursor.getString(1),
                    cursor.isNull(2) ? null : cursor.getLong(2));
        }
    }

    /** Blocks additional writes while any previous provider outcome is uncertain. */
    public boolean hasUnresolvedWrites(String exceptOperationId) {
        try (Cursor cursor = getReadableDatabase().query("writes", new String[]{"operation_id"},
                "state = ? AND operation_id != ?", new String[]{"pending", exceptOperationId},
                null, null, null, "1")) {
            return cursor.moveToFirst();
        }
    }

    /** Reopens a definitively failed atomic provider batch with the same fingerprint. */
    public void restart(String operationId, String fingerprint) {
        ContentValues values = new ContentValues();
        values.put("state", "pending");
        values.putNull("destination_id");
        int changed = getWritableDatabase().update("writes", values,
                "operation_id = ? AND fingerprint = ? AND state = ?",
                new String[]{operationId, fingerprint, "failed"});
        if (changed != 1) {
            throw new IllegalStateException("Failed write cannot be safely retried");
        }
    }

    /** Marks a provider-rejected atomic batch as retryable rather than uncertain. */
    public void fail(String operationId) {
        ContentValues values = new ContentValues();
        values.put("state", "failed");
        int changed = getWritableDatabase().update("writes", values,
                "operation_id = ? AND state = ?", new String[]{operationId, "pending"});
        if (changed != 1) {
            throw new IllegalStateException("Write journal entry is not pending");
        }
    }

    /** Records the intent before any destination mutation is attempted. */
    public void begin(String operationId, String fingerprint) {
        ContentValues values = new ContentValues();
        values.put("operation_id", operationId);
        values.put("fingerprint", fingerprint);
        values.put("state", "pending");
        getWritableDatabase().insertOrThrow("writes", null, values);
    }

    /** Records success only after the provider mutation and success log complete. */
    public void complete(String operationId, long destinationId) {
        ContentValues values = new ContentValues();
        values.put("state", "complete");
        values.put("destination_id", destinationId);
        int changed = getWritableDatabase().update("writes", values,
                "operation_id = ? AND state = ?", new String[]{operationId, "pending"});
        if (changed != 1) {
            throw new IllegalStateException("Write journal entry is not pending");
        }
    }
}
