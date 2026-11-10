package org.synccontacts.app;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.ContactsContract;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Executes explicitly prepared additive raw-contact writes with provider version
 * assertions and a durable fail-closed journal. It does not choose matches.
 */
public final class GuardedContactWriter {
    private static final Object LOCK = new Object();
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE);

    /** Identifies a raw contact, its exact account, and the version that was read. */
    public record VersionedContact(long rawId, long version, AddressBook book) {
        public VersionedContact {
            if (rawId <= 0 || version <= 0 || book == null) {
                throw new IllegalArgumentException("Invalid raw-contact identity");
            }
        }
    }

    private final Context context;
    private final ContentResolver resolver;
    private final ActivityLog log;

    /** Uses app-private logging and journal storage with the supplied provider. */
    public GuardedContactWriter(Context context) {
        this.context = context;
        this.resolver = context.getContentResolver();
        this.log = new ActivityLog(context);
    }

    /**
     * Creates a destination raw contact and its provided rows in one provider
     * batch. Callers must establish that creating it is appropriate.
     */
    public long create(String operationId, VersionedContact source, AddressBook destination,
            List<ContentValues> rows) throws IOException, RemoteException,
            OperationApplicationException {
        validatePair(source, destination);
        List<ContentValues> data = checkedRows(rows);
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        operations.add(assertVersion(source));

        // The raw contact and its rows are one atomic Contacts Provider batch.
        ContentValues raw = new ContentValues();
        putNullable(raw, ContactsContract.RawContacts.ACCOUNT_NAME, destination.accountName());
        putNullable(raw, ContactsContract.RawContacts.ACCOUNT_TYPE, destination.accountType());
        putNullable(raw, ContactsContract.RawContacts.DATA_SET, destination.dataSet());
        operations.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValues(raw).build());
        for (ContentValues row : data) {
            operations.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValues(row)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 1).build());
        }
        String fingerprint = fingerprint("create", source, destination, null, data);
        return execute(operationId, fingerprint, source, destination, null, data, operations, 1);
    }

    /**
     * Adds caller-verified missing rows to one version-checked destination.
     * The caller must not include values already present in the destination.
     */
    public long append(String operationId, VersionedContact source, VersionedContact destination,
            List<ContentValues> rows) throws IOException, RemoteException,
            OperationApplicationException {
        validatePair(source, destination.book());
        List<ContentValues> data = checkedRows(rows);
        if (data.isEmpty()) {
            throw new IllegalArgumentException("No destination rows to append");
        }
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        operations.add(assertVersion(source));
        operations.add(assertVersion(destination));
        for (ContentValues row : data) {
            ContentValues insert = new ContentValues(row);
            insert.put(ContactsContract.Data.RAW_CONTACT_ID, destination.rawId());
            operations.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValues(insert).build());
        }
        String fingerprint = fingerprint("append", source, destination.book(), destination, data);
        return execute(operationId, fingerprint, source, destination.book(), destination,
                data, operations, -1);
    }

    /**
     * Replaces exactly one conflicted MIME group after explicit user approval.
     * Other destination MIME groups and fields are not touched.
     */
    public long replaceField(String operationId, VersionedContact source,
            VersionedContact destination, String mime, List<ContentValues> rows)
            throws IOException, RemoteException, OperationApplicationException {
        validatePair(source, destination.book());
        if (!SUPPORTED_TYPES.contains(mime)) {
            throw new IllegalArgumentException("Unsupported contact data type");
        }
        List<ContentValues> data = checkedRows(rows);
        for (ContentValues row : data) {
            if (!mime.equals(row.getAsString(ContactsContract.Data.MIMETYPE))) {
                throw new IllegalArgumentException("Replacement rows contain another data type");
            }
        }
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        operations.add(assertVersion(source));
        operations.add(assertVersion(destination));
        operations.add(ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data.RAW_CONTACT_ID + " = ? AND "
                                + ContactsContract.Data.MIMETYPE + " = ?",
                        new String[]{Long.toString(destination.rawId()), mime}).build());
        for (ContentValues row : data) {
            ContentValues insert = new ContentValues(row);
            insert.put(ContactsContract.Data.RAW_CONTACT_ID, destination.rawId());
            operations.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValues(insert).build());
        }
        String fingerprint = fingerprint("replace:" + mime, source, destination.book(),
                destination, data);
        return execute(operationId, fingerprint, source, destination.book(), destination,
                data, operations, -1);
    }

    /**
     * Deletes a linked destination raw contact only if the source is still absent
     * from the same book and both account identities remain exact.
     */
    public long deleteLinked(String operationId, long sourceId, AddressBook sourceBook,
            VersionedContact destination) throws IOException, RemoteException,
            OperationApplicationException {
        validatePair(new VersionedContact(sourceId, 1, sourceBook), destination.book());
        if (sourceId <= 0) {
            throw new IllegalArgumentException("Invalid deleted source identifier");
        }
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        operations.add(assertAbsent(sourceId, sourceBook));
        operations.add(assertVersion(destination));
        List<String> deleteArguments = new ArrayList<>();
        deleteArguments.add(Long.toString(destination.version()));
        String deleteSelection = ContactsContract.RawContacts.VERSION + " = ? AND "
                + ContactsContract.RawContacts.DELETED + " = 0 AND "
                + condition(ContactsContract.RawContacts.ACCOUNT_NAME,
                        destination.book().accountName(), deleteArguments)
                + " AND " + condition(ContactsContract.RawContacts.ACCOUNT_TYPE,
                        destination.book().accountType(), deleteArguments)
                + " AND " + condition(ContactsContract.RawContacts.DATA_SET,
                        destination.book().dataSet(), deleteArguments);
        operations.add(ContentProviderOperation.newDelete(
                        ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI,
                                destination.rawId()))
                .withSelection(deleteSelection, deleteArguments.toArray(new String[0]))
                .withExpectedCount(1).build());
        String fingerprint = fingerprint("delete:" + sourceId,
                new VersionedContact(sourceId, 1, sourceBook), destination.book(),
                destination, List.of());
        return execute(operationId, fingerprint,
                new VersionedContact(sourceId, 1, sourceBook), destination.book(), destination,
                List.of(), operations, -1);
    }

    /** Journals and logs before writing; an uncertain prior write cannot be repeated. */
    private long execute(String operationId, String fingerprint, VersionedContact source,
            AddressBook destinationBook, VersionedContact destination, List<ContentValues> data,
            ArrayList<ContentProviderOperation> operations, int insertedRawIndex)
            throws IOException, RemoteException, OperationApplicationException {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("A durable operation identifier is required");
        }
        synchronized (LOCK) {
            try (ContactWriteJournal journal = new ContactWriteJournal(context)) {
                ContactWriteJournal.Entry previous = journal.find(operationId);
                boolean retryingFailedWrite = false;
                if (previous != null) {
                    if (!previous.fingerprint().equals(fingerprint)) {
                        throw new IllegalStateException("Operation identifier was reused");
                    }
                    if ("complete".equals(previous.state()) && previous.destinationId() != null) {
                        return previous.destinationId();
                    }
                    if ("pending".equals(previous.state())) {
                        throw new IllegalStateException("Previous write outcome is uncertain; review required");
                    }
                    retryingFailedWrite = true;
                }
                if (journal.hasUnresolvedWrites(operationId)) {
                    throw new IllegalStateException(
                            "A previous destination write needs review before further writes");
                }

                // A failed log or journal write prevents the provider call altogether.
                log.append("Destination write intent " + operationId + " from raw "
                        + source.rawId() + " to " + destinationBook.label() + ": "
                        + describeRows(data));
                if (retryingFailedWrite) {
                    journal.restart(operationId, fingerprint);
                } else {
                    journal.begin(operationId, fingerprint);
                }
                try {
                    ContentProviderResult[] result = resolver.applyBatch(
                            ContactsContract.AUTHORITY, operations);
                    long id = insertedRawIndex < 0 ? destination.rawId()
                            : insertedId(result, insertedRawIndex);
                    log.append("Destination write completed " + operationId + " raw " + id);
                    journal.complete(operationId, id);
                    return id;
                } catch (OperationApplicationException error) {
                    journal.fail(operationId);
                    try {
                        log.append("Destination write rejected " + operationId + ": "
                                + error.getMessage());
                    } catch (IOException logError) {
                        error.addSuppressed(logError);
                    }
                    throw error;
                } catch (IOException | RemoteException | RuntimeException error) {
                    // Pending is deliberately retained: the provider outcome might be unknown.
                    try {
                        log.append("Destination write failed or has uncertain outcome "
                                + operationId + ": " + error.getClass().getSimpleName()
                                + ": " + error.getMessage());
                    } catch (IOException logError) {
                        error.addSuppressed(logError);
                    }
                    throw error;
                }
            }
        }
    }

    /** Checks that a returned raw-contact insert actually has a provider URI. */
    private static long insertedId(ContentProviderResult[] results, int index) throws IOException {
        if (index >= results.length || results[index] == null || results[index].uri == null) {
            throw new IOException("Provider did not return the created raw contact");
        }
        try {
            long id = ContentUris.parseId(results[index].uri);
            if (id <= 0) {
                throw new IOException("Provider returned an invalid raw-contact ID");
            }
            return id;
        } catch (NumberFormatException error) {
            throw new IOException("Provider returned an invalid raw-contact URI", error);
        }
    }

    /** Asserts ID, version, non-deletion, and exact account within the provider transaction. */
    private static ContentProviderOperation assertVersion(VersionedContact contact) {
        List<String> arguments = new ArrayList<>(Arrays.asList(
                Long.toString(contact.rawId()), Long.toString(contact.version())));
        String selection = ContactsContract.RawContacts._ID + " = ? AND "
                + ContactsContract.RawContacts.VERSION + " = ? AND "
                + ContactsContract.RawContacts.DELETED + " = 0 AND "
                + condition(ContactsContract.RawContacts.ACCOUNT_NAME, contact.book().accountName(), arguments)
                + " AND " + condition(ContactsContract.RawContacts.ACCOUNT_TYPE,
                        contact.book().accountType(), arguments)
                + " AND " + condition(ContactsContract.RawContacts.DATA_SET,
                        contact.book().dataSet(), arguments);
        return ContentProviderOperation.newAssertQuery(ContactsContract.RawContacts.CONTENT_URI)
                .withSelection(selection, arguments.toArray(new String[0]))
                .withExpectedCount(1).build();
    }

    /** Asserts that a deleted source identity is still absent in its selected account. */
    private static ContentProviderOperation assertAbsent(long sourceId, AddressBook book) {
        List<String> arguments = new ArrayList<>();
        arguments.add(Long.toString(sourceId));
        String selection = ContactsContract.RawContacts._ID + " = ? AND "
                + ContactsContract.RawContacts.DELETED + " = 0 AND "
                + condition(ContactsContract.RawContacts.ACCOUNT_NAME, book.accountName(), arguments)
                + " AND " + condition(ContactsContract.RawContacts.ACCOUNT_TYPE,
                        book.accountType(), arguments)
                + " AND " + condition(ContactsContract.RawContacts.DATA_SET, book.dataSet(), arguments);
        return ContentProviderOperation.newAssertQuery(ContactsContract.RawContacts.CONTENT_URI)
                .withSelection(selection, arguments.toArray(new String[0]))
                .withExpectedCount(0).build();
    }

    /** Preserves null account identity in SQL without interpolating user-controlled values. */
    private static String condition(String column, String value, List<String> arguments) {
        if (value == null) {
            return column + " IS NULL";
        }
        arguments.add(value);
        return column + " = ?";
    }

    /** Prevents writes into the same selected book or an incomplete account identity. */
    private static void validatePair(VersionedContact source, AddressBook destination) {
        if (source == null || destination == null || source.book().equals(destination)) {
            throw new IllegalArgumentException("Source and destination books must be distinct");
        }
        if ((destination.accountName() == null) != (destination.accountType() == null)) {
            throw new IllegalArgumentException("Incomplete destination account identity");
        }
    }

    /** Copies only approved MIME types and data columns, preserving binary values. */
    private static List<ContentValues> checkedRows(List<ContentValues> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("At least one contact data row is required");
        }
        List<ContentValues> checked = new ArrayList<>();
        for (ContentValues row : rows) {
            if (row == null || !SUPPORTED_TYPES.contains(row.getAsString(ContactsContract.Data.MIMETYPE))) {
                throw new IllegalArgumentException("Unsupported contact data type");
            }
            ContentValues copy = new ContentValues();
            for (String key : row.keySet()) {
                if (!ContactsContract.Data.MIMETYPE.equals(key) && !isDataColumn(key)) {
                    throw new IllegalArgumentException("Unsupported contact data column: " + key);
                }
                Object value = row.get(key);
                if (value instanceof byte[]) {
                    copy.put(key, ((byte[]) value).clone());
                } else if (value instanceof String) {
                    copy.put(key, (String) value);
                } else if (value instanceof Long) {
                    copy.put(key, (Long) value);
                } else if (value instanceof Integer) {
                    copy.put(key, (Integer) value);
                } else if (value == null) {
                    copy.putNull(key);
                } else {
                    throw new IllegalArgumentException("Unsupported contact data value for " + key);
                }
            }
            checked.add(copy);
        }
        return checked;
    }

    /** Identifies only data1 through data15, excluding provider-managed columns. */
    private static boolean isDataColumn(String key) {
        for (int i = 1; i <= 15; i++) {
            if (("data" + i).equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** Stores a nullable account field explicitly in provider values. */
    private static void putNullable(ContentValues values, String key, String value) {
        if (value == null) {
            values.putNull(key);
        } else {
            values.put(key, value);
        }
    }

    /** Hashes the complete operation so a reused ID cannot silently change meaning. */
    private static String fingerprint(String action, VersionedContact source, AddressBook book,
            VersionedContact destination, List<ContentValues> rows) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writeText(out, action);
            writeContact(out, source);
            writeBook(out, book);
            out.writeBoolean(destination != null);
            if (destination != null) {
                writeContact(out, destination);
            }
            out.writeInt(rows.size());
            for (ContentValues row : rows) {
                List<String> keys = new ArrayList<>(row.keySet());
                Collections.sort(keys);
                out.writeInt(keys.size());
                for (String key : keys) {
                    writeText(out, key);
                    Object value = row.get(key);
                    if (value instanceof byte[]) {
                        out.writeByte(1);
                        byte[] binary = (byte[]) value;
                        out.writeInt(binary.length);
                        out.write(binary);
                    } else {
                        out.writeByte(0);
                        writeText(out, value == null ? null : value.getClass().getName() + ":" + value);
                    }
                }
            }
        }
        return hexDigest(bytes.toByteArray());
    }

    /** Writes unambiguous length-prefixed UTF-8 rather than delimiter-based values. */
    private static void writeText(DataOutputStream out, String text) throws IOException {
        if (text == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /** Includes both provider identity and version in the fingerprint. */
    private static void writeContact(DataOutputStream out, VersionedContact contact) throws IOException {
        out.writeLong(contact.rawId());
        out.writeLong(contact.version());
        writeBook(out, contact.book());
    }

    /** Keeps null account values distinct from empty account values. */
    private static void writeBook(DataOutputStream out, AddressBook book) throws IOException {
        writeText(out, book.accountName());
        writeText(out, book.accountType());
        writeText(out, book.dataSet());
    }

    /** Returns a deterministic SHA-256 digest without exposing contact data. */
    private static String hexDigest(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    /** Formats intended values for the private log without dumping binary bytes. */
    private static String describeRows(List<ContentValues> rows) {
        List<String> descriptions = new ArrayList<>();
        for (ContentValues row : rows) {
            List<String> keys = new ArrayList<>(row.keySet());
            Collections.sort(keys);
            List<String> values = new ArrayList<>();
            for (String key : keys) {
                Object value = row.get(key);
                if (value instanceof byte[]) {
                    byte[] binary = (byte[]) value;
                    values.add(key + "=binary length=" + binary.length
                            + " sha256=" + hexDigest(binary));
                } else {
                    values.add(key + "=" + value);
                }
            }
            descriptions.add(values.toString());
        }
        return descriptions.toString();
    }
}
