package org.synccontacts.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks reconciliation plans against synthetic Contacts Provider rows. */
@RunWith(AndroidJUnit4.class)
public final class PlannerProviderTest {
    private static final String NAME = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE;
    private static final String PHONE = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE;
    private static final String EMAIL = ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE;
    private static final Map<String, ContactComparison.Cardinality> FIELDS = Map.of(
            NAME, ContactComparison.Cardinality.SINGLE,
            EMAIL, ContactComparison.Cardinality.MULTIPLE);

    /** Reads real provider rows and checks no-op, additive, and conflict plans. */
    @Test
    public void plansChangesFromSyntheticProviderContacts() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ContentResolver resolver = context.getContentResolver();
        Uri source = insertContact(resolver, "Synthetic Alice", "+49 (30) 12345678");
        Uri destination = null;
        try {
            destination = insertContact(resolver, "Synthetic Alice", "0049 30 12345678");
            long sourceId = ContentUris.parseId(source);
            long destinationId = ContentUris.parseId(destination);
            var initial = plan(resolver, sourceId, destinationId, List.of());
            assertEquals(ReconciliationPlanner.Action.RECONCILE, initial.action());
            assertTrue(initial.comparison().additions().isEmpty());
            assertTrue(initial.comparison().conflicts().isEmpty());

            insertData(resolver, sourceId, EMAIL, "synthetic@example.test");
            var added = plan(resolver, sourceId, destinationId, List.of());
            assertEquals(Set.of(new ContactComparison.Value("synthetic@example.test", "", "")),
                    added.comparison().additions().get(EMAIL));
            assertTrue(added.comparison().conflicts().isEmpty());

            changeName(resolver, destinationId, "Synthetic Bob");
            var conflict = plan(resolver, sourceId, destinationId,
                    List.of(new ReconciliationPlanner.Link(Long.toString(sourceId),
                            Long.toString(destinationId))));
            assertEquals(1, conflict.comparison().conflicts().size());
            assertEquals(NAME, conflict.comparison().conflicts().get(0).field());
        } finally {
            resolver.delete(source, null, null);
            if (destination != null) {
                resolver.delete(destination, null, null);
            }
        }
    }

    /** Creates a local test contact with a name and a telephone number. */
    private Uri insertContact(ContentResolver resolver, String name, String phone) {
        ContentValues raw = new ContentValues();
        raw.putNull(ContactsContract.RawContacts.ACCOUNT_NAME);
        raw.putNull(ContactsContract.RawContacts.ACCOUNT_TYPE);
        Uri contact = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, raw);
        assertNotNull(contact);
        try {
            long id = ContentUris.parseId(contact);
            insertData(resolver, id, NAME, name);
            insertData(resolver, id, PHONE, phone);
            return contact;
        } catch (RuntimeException | AssertionError error) {
            resolver.delete(contact, null, null);
            throw error;
        }
    }

    /** Inserts a single user-editable value for a synthetic raw contact. */
    private void insertData(ContentResolver resolver, long id, String mime, String value) {
        ContentValues row = new ContentValues();
        row.put(ContactsContract.Data.RAW_CONTACT_ID, id);
        row.put(ContactsContract.Data.MIMETYPE, mime);
        row.put(ContactsContract.Data.DATA1, value);
        assertNotNull(resolver.insert(ContactsContract.Data.CONTENT_URI, row));
    }

    /** Changes only the synthetic destination's name row. */
    private void changeName(ContentResolver resolver, long id, String name) {
        ContentValues values = new ContentValues();
        values.put(ContactsContract.Data.DATA1, name);
        assertEquals(1, resolver.update(ContactsContract.Data.CONTENT_URI, values,
                ContactsContract.Data.RAW_CONTACT_ID + " = ? AND "
                        + ContactsContract.Data.MIMETYPE + " = ?",
                new String[]{Long.toString(id), NAME}));
    }

    /** Builds a plan using freshly read source and destination provider data. */
    private ReconciliationPlanner.Plan plan(ContentResolver resolver, long source, long destination,
            List<ReconciliationPlanner.Link> links) {
        return ReconciliationPlanner.plan(List.of(read(resolver, source)),
                List.of(read(resolver, destination)), links, FIELDS).get(0);
    }

    /** Converts the limited synthetic test fields into the planner's normalized model. */
    private ReconciliationPlanner.Contact read(ContentResolver resolver, long id) {
        Set<String> phones = new LinkedHashSet<>();
        Map<String, Set<ContactComparison.Value>> fields = new LinkedHashMap<>();
        String[] columns = {ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1};
        try (Cursor cursor = resolver.query(ContactsContract.Data.CONTENT_URI, columns,
                ContactsContract.Data.RAW_CONTACT_ID + " = ?", new String[]{Long.toString(id)}, null)) {
            assertNotNull(cursor);
            while (cursor.moveToNext()) {
                String mime = cursor.getString(0);
                String value = cursor.getString(1);
                if (value == null) {
                    continue;
                }
                if (PHONE.equals(mime)) {
                    phones.add(value);
                } else if (FIELDS.containsKey(mime)) {
                    fields.computeIfAbsent(mime, ignored -> new LinkedHashSet<>())
                            .add(new ContactComparison.Value(value, "", ""));
                }
            }
        }
        return new ReconciliationPlanner.Contact(Long.toString(id), phones, fields);
    }
}
