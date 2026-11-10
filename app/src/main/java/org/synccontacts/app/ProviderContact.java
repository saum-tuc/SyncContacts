package org.synccontacts.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A versioned raw contact with loss-aware supported-field projections. */
public final class ProviderContact {
    public static final String NAME = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE;
    public static final String PHONE = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE;
    public static final String EMAIL = ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE;
    public static final String POSTAL = ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE;
    public static final String ORGANIZATION = ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE;
    public static final String NOTE = ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE;
    public static final String EVENT = ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE;
    public static final String WEBSITE = ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE;
    public static final String PHOTO = ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE;
    public static final Map<String, ContactComparison.Cardinality> FIELD_TYPES = Map.of(
            NAME, ContactComparison.Cardinality.SINGLE,
            PHONE, ContactComparison.Cardinality.MULTIPLE,
            EMAIL, ContactComparison.Cardinality.MULTIPLE,
            POSTAL, ContactComparison.Cardinality.MULTIPLE,
            ORGANIZATION, ContactComparison.Cardinality.SINGLE,
            NOTE, ContactComparison.Cardinality.MULTIPLE,
            EVENT, ContactComparison.Cardinality.MULTIPLE,
            WEBSITE, ContactComparison.Cardinality.MULTIPLE,
            PHOTO, ContactComparison.Cardinality.SINGLE);

    private final GuardedContactWriter.VersionedContact identity;
    private final Map<String, Set<ContactComparison.Value>> fields;
    private final Map<String, Map<ContactComparison.Value, ContentValues>> rows;
    private final Set<String> unsupported;

    /** Captures the account-scoped identity, fields, and source rows. */
    private ProviderContact(GuardedContactWriter.VersionedContact identity,
            Map<String, Set<ContactComparison.Value>> fields,
            Map<String, Map<ContactComparison.Value, ContentValues>> rows, Set<String> unsupported) {
        this.identity = identity;
        this.fields = fields;
        this.rows = rows;
        this.unsupported = unsupported;
    }

    /** Returns the versioned raw-contact identity for optimistic concurrency checks. */
    public GuardedContactWriter.VersionedContact identity() {
        return identity;
    }

    /** Returns supported field values with labels and types included in their identities. */
    public Map<String, Set<ContactComparison.Value>> fields() {
        return fields;
    }

    /** Returns all phone numbers for conservative identity matching. */
    public Set<String> phoneNumbers() {
        Set<String> result = new LinkedHashSet<>();
        for (ContactComparison.Value value : fields.getOrDefault(PHONE, Set.of())) {
            try {
                JSONObject encoded = new JSONObject(value.value());
                result.add(encoded.optString("data1", ""));
            } catch (JSONException error) {
                throw new IllegalStateException("Invalid stored phone value", error);
            }
        }
        return Set.copyOf(result);
    }

    /** Returns the contact display name for logs and completed-sync summaries. */
    public String displayName() {
        for (ContactComparison.Value value : fields.getOrDefault(NAME, Set.of())) {
            try {
                JSONObject nameData = new JSONObject(value.value());
                String name = nameData.optString("data1", "").trim();
                if (name.isEmpty()) {
                    name = String.join(" ", List.of(
                            nameData.optString("data4", ""),
                            nameData.optString("data2", ""),
                            nameData.optString("data5", ""),
                            nameData.optString("data3", ""),
                            nameData.optString("data6", ""))).trim().replaceAll("\\s+", " ");
                }
                if (!name.isEmpty()) {
                    return name;
                }
            } catch (JSONException error) {
                throw new IllegalStateException("Invalid stored contact name", error);
            }
        }
        return "Unnamed contact (" + identity.rawId() + ")";
    }

    /** Returns an insertable copy of the exact source rows for planned values. */
    public List<ContentValues> rows(String mime, Set<ContactComparison.Value> values) {
        Map<ContactComparison.Value, ContentValues> available = rows.getOrDefault(mime, Map.of());
        List<ContentValues> result = new ArrayList<>();
        for (ContactComparison.Value value : values) {
            ContentValues row = available.get(value);
            if (row == null) {
                throw new IllegalStateException("Planned source row is no longer available");
            }
            result.add(new ContentValues(row));
        }
        return List.copyOf(result);
    }

    /** Returns the supported MIME types that cannot safely be copied. */
    public Set<String> unsupported() {
        return unsupported;
    }

    /** Reads one raw contact and its rows only if its current account identity matches. */
    public static ProviderContact read(ContentResolver resolver, long rawId, AddressBook expected)
            throws IOException {
        String[] columns = {ContactsContract.RawContacts._ID, ContactsContract.RawContacts.VERSION,
                ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.DATA_SET, ContactsContract.RawContacts.DELETED};
        List<String> arguments = new ArrayList<>();
        arguments.add(Long.toString(rawId));
        String selection = ContactsContract.RawContacts._ID + " = ? AND "
                + ContactsContract.RawContacts.DELETED + " = 0 AND "
                + accountCondition(ContactsContract.RawContacts.ACCOUNT_NAME,
                        expected.accountName(), arguments)
                + " AND " + accountCondition(ContactsContract.RawContacts.ACCOUNT_TYPE,
                        expected.accountType(), arguments)
                + " AND " + accountCondition(ContactsContract.RawContacts.DATA_SET,
                        expected.dataSet(), arguments);
        try (Cursor cursor = resolver.query(ContactsContract.RawContacts.CONTENT_URI, columns,
                selection, arguments.toArray(new String[0]), null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new IOException("Raw contact is missing or belongs to a different address book");
            }
            AddressBook actual = AddressBook.fromCursor(cursor);
            if (!expected.equals(actual)) {
                throw new IOException("Raw contact account identity changed");
            }
            long version = cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.VERSION));
            GuardedContactWriter.VersionedContact identity =
                    new GuardedContactWriter.VersionedContact(rawId, version, actual);
            return readRows(resolver, identity);
        }
    }

    /** Reads all data rows and retains unsupported MIME types for visible warnings. */
    private static ProviderContact readRows(ContentResolver resolver,
            GuardedContactWriter.VersionedContact identity) throws IOException {
        String[] columns = new String[16];
        columns[0] = ContactsContract.Data.MIMETYPE;
        for (int i = 1; i <= 15; i++) {
            columns[i] = "data" + i;
        }
        Map<String, Set<ContactComparison.Value>> fields = new LinkedHashMap<>();
        Map<String, Map<ContactComparison.Value, ContentValues>> rows = new LinkedHashMap<>();
        Set<String> unsupported = new LinkedHashSet<>();
        try (Cursor cursor = resolver.query(ContactsContract.Data.CONTENT_URI, columns,
                ContactsContract.Data.RAW_CONTACT_ID + " = ?",
                new String[]{Long.toString(identity.rawId())}, null)) {
            if (cursor == null) {
                throw new IOException("Unable to read contact data");
            }
            while (cursor.moveToNext()) {
                String mime = cursor.getString(0);
                if (!FIELD_TYPES.containsKey(mime)) {
                    unsupported.add(mime == null ? "(missing MIME type)" : mime);
                    continue;
                }
                ContentValues row = readRow(cursor, mime);
                ContactComparison.Value value = identity(mime, row);
                fields.computeIfAbsent(mime, ignored -> new LinkedHashSet<>()).add(value);
                rows.computeIfAbsent(mime, ignored -> new LinkedHashMap<>()).put(value, row);
            }
        } catch (JSONException error) {
            throw new IOException("Unable to interpret contact data", error);
        }
        return new ProviderContact(identity, immutableFields(fields), immutableRows(rows),
                Set.copyOf(unsupported));
    }

    /** Copies only data columns, preserving nullability and binary data. */
    private static ContentValues readRow(Cursor cursor, String mime) {
        ContentValues row = new ContentValues();
        row.put(ContactsContract.Data.MIMETYPE, mime);
        for (int i = 1; i < cursor.getColumnCount(); i++) {
            if (cursor.isNull(i)) {
                continue;
            }
            String key = cursor.getColumnName(i);
            if (cursor.getType(i) == Cursor.FIELD_TYPE_BLOB) {
                row.put(key, cursor.getBlob(i));
            } else if (cursor.getType(i) == Cursor.FIELD_TYPE_INTEGER) {
                row.put(key, cursor.getLong(i));
            } else {
                row.put(key, cursor.getString(i));
            }
        }
        return row;
    }

    /** Builds a stable comparison key while separating phone label metadata. */
    private static ContactComparison.Value identity(String mime, ContentValues row)
            throws JSONException {
        JSONObject data = new JSONObject();
        for (int i = 1; i <= 15; i++) {
            String key = "data" + i;
            if (!row.containsKey(key)) {
                continue;
            }
            // data4 is a provider-derived phone normalization, not user contact data.
            if (PHONE.equals(mime) && "data4".equals(key)) {
                continue;
            }
            if ((PHONE.equals(mime) || EMAIL.equals(mime)) && ("data2".equals(key) || "data3".equals(key))) {
                continue;
            }
            Object value = row.get(key);
            if (value instanceof byte[]) {
                data.put(key, Base64.encodeToString((byte[]) value, Base64.NO_WRAP));
            } else if (value instanceof Number) {
                data.put(key, ((Number) value).longValue());
            } else {
                String text = String.valueOf(value);
                if (PHONE.equals(mime) && "data1".equals(key)) {
                    text = PhoneNumbers.key(text).orElse(text.trim());
                }
                data.put(key, text);
            }
        }
        String type = (PHONE.equals(mime) || EMAIL.equals(mime))
                ? nullableLabel(row.getAsString("data2")) : "";
        String label = (PHONE.equals(mime) || EMAIL.equals(mime))
                ? nullableLabel(row.getAsString("data3")) : "";
        return new ContactComparison.Value(data.toString(), type, label);
    }

    /** Keeps null type and label values distinct from a literal label named "null". */
    private static String nullableLabel(String value) {
        return value == null ? "\u0000" : value;
    }

    /** Preserves null account identity in an exact, parameter-free predicate. */
    private static String accountCondition(String column, String value, List<String> arguments) {
        if (value == null) {
            return column + " IS NULL";
        }
        arguments.add(value);
        return column + " = ?";
    }

    /** Makes immutable field sets safe to pass to the planner. */
    private static Map<String, Set<ContactComparison.Value>> immutableFields(
            Map<String, Set<ContactComparison.Value>> fields) {
        Map<String, Set<ContactComparison.Value>> result = new LinkedHashMap<>();
        fields.forEach((mime, values) -> result.put(mime, Set.copyOf(values)));
        return Map.copyOf(result);
    }

    /** Makes immutable row maps safe to retain across planning. */
    private static Map<String, Map<ContactComparison.Value, ContentValues>> immutableRows(
            Map<String, Map<ContactComparison.Value, ContentValues>> rows) {
        Map<String, Map<ContactComparison.Value, ContentValues>> result = new LinkedHashMap<>();
        rows.forEach((mime, values) -> result.put(mime, Map.copyOf(values)));
        return Map.copyOf(result);
    }

    /** Builds a provider-independent planner record. */
    public ReconciliationPlanner.Contact plannerRecord() {
        return new ReconciliationPlanner.Contact(Long.toString(identity.rawId()),
                phoneNumbers(), fields);
    }
}
