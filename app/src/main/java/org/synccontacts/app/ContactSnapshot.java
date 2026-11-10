package org.synccontacts.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Captures account-scoped source data for detecting observed changes. */
public final class ContactSnapshot {
    private final ContentResolver resolver;

    /** Uses the current Android Contacts Provider. */
    public ContactSnapshot(ContentResolver resolver) {
        this.resolver = resolver;
    }

    /** Reads each raw contact and its data rows without accessing other accounts. */
    public JSONObject read(AddressBook book) throws JSONException, IOException {
        String[] columns = {ContactsContract.RawContacts._ID};
        List<String> args = new ArrayList<>();
        String selection = "deleted = 0 AND " + accountSelection(book, args);
        JSONObject snapshot = new JSONObject();
        try (Cursor cursor = resolver.query(ContactsContract.RawContacts.CONTENT_URI, columns,
                selection, args.toArray(new String[0]), null)) {
            if (cursor == null) {
                throw new IOException("Unable to read source contacts");
            }
            while (cursor.moveToNext()) {
                long rawId = cursor.getLong(0);
                snapshot.put(Long.toString(rawId), readData(rawId));
            }
        }
        return snapshot;
    }

    /** Builds a parameterized account predicate that preserves null semantics. */
    private static String accountSelection(AddressBook book, List<String> args) {
        return condition(ContactsContract.RawContacts.ACCOUNT_NAME, book.accountName(), args)
                + " AND " + condition(ContactsContract.RawContacts.ACCOUNT_TYPE, book.accountType(), args)
                + " AND " + condition(ContactsContract.RawContacts.DATA_SET, book.dataSet(), args);
    }

    /** Adds a bound argument only when the account component is non-null. */
    private static String condition(String column, String value, List<String> args) {
        if (value == null) {
            return column + " IS NULL";
        }
        args.add(value);
        return column + " = ?";
    }

    /** Reads data rows in deterministic order while replacing binary bytes with digests. */
    private JSONArray readData(long rawId) throws IOException, JSONException {
        String[] columns = new String[16];
        columns[0] = ContactsContract.Data.MIMETYPE;
        for (int i = 1; i <= 15; i++) {
            columns[i] = "data" + i;
        }
        List<String> rows = new ArrayList<>();
        try (Cursor cursor = resolver.query(ContactsContract.Data.CONTENT_URI, columns,
                ContactsContract.Data.RAW_CONTACT_ID + " = ?",
                new String[]{Long.toString(rawId)}, null)) {
            if (cursor == null) {
                throw new IOException("Unable to read data for raw contact " + rawId);
            }
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < columns.length; i++) {
                    if (cursor.isNull(i)) {
                        continue;
                    }
                    String value = cursor.getType(i) == Cursor.FIELD_TYPE_BLOB
                            ? describeBytes(cursor.getBlob(i)) : cursor.getString(i);
                    row.put(columns[i], value);
                }
                rows.add(row.toString());
            }
        }
        Collections.sort(rows);
        return new JSONArray(rows);
    }

    /** Represents binary content by length and digest, not raw contact photos. */
    private static String describeBytes(byte[] bytes) {
        return "binary length=" + bytes.length + " sha256=" + digest(bytes);
    }

    /** Computes a lowercase SHA-256 digest for binary data and snapshot names. */
    private static String digest(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    /** Separates snapshots by selected pair without exposing account names in filenames. */
    public static AtomicFile file(File directory, AddressBook source, AddressBook destination)
            throws JSONException {
        String pair = source.encode() + "\n" + destination.encode();
        return new AtomicFile(new File(directory,
                "snapshot-" + digest(pair.getBytes(StandardCharsets.UTF_8)) + ".json"));
    }

    /** Loads a previous complete snapshot, or null when no scan has completed. */
    public static JSONObject load(AtomicFile file) throws IOException, JSONException {
        byte[] contents = AtomicFiles.readIfPresent(file);
        if (contents == null) {
            return null;
        }
        return new JSONObject(new String(contents, StandardCharsets.UTF_8));
    }

    /** Atomically commits a complete snapshot after its change events are logged. */
    public static void save(AtomicFile file, JSONObject snapshot) throws IOException {
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(snapshot.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (IOException | RuntimeException error) {
            file.failWrite(stream);
            throw error;
        }
    }
}
