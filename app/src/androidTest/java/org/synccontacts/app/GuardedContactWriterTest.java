package org.synccontacts.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.provider.ContactsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Exercises actual destination writes using synthetic contacts on an isolated emulator. */
@RunWith(AndroidJUnit4.class)
public final class GuardedContactWriterTest {
    private static final AddressBook SOURCE_BOOK =
            new AddressBook("synthetic-source", "org.synccontacts.synthetic", null);
    private static final AddressBook DESTINATION_BOOK = new AddressBook(null, null, null);
    private Context context;
    private Context target;
    private File files;
    private String prefix;

    /** Separates test journal and log files from the app's normal private storage. */
    @Before
    public void isolateStorage() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix = "writer-test-" + UUID.randomUUID() + "-";
        files = new File(target.getCacheDir(), prefix);
        assertTrue(files.isDirectory() || files.mkdirs());
        context = new ContextWrapper(target) {
            @Override
            public File getFilesDir() {
                return files;
            }

            @Override
            public File getDatabasePath(String name) {
                return target.getDatabasePath(prefix + name);
            }

            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                return target.getSharedPreferences(prefix + name, mode);
            }

            @Override
            public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                    SQLiteDatabase.CursorFactory factory) {
                return target.openOrCreateDatabase(prefix + name, mode, factory);
            }

            @Override
            public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                    SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
                return target.openOrCreateDatabase(prefix + name, mode, factory, handler);
            }
        };
        new SyncSettings(context).setLoggingEnabled(true);
    }

    /** Removes only this test's named database and private log files. */
    @After
    public void cleanStorage() {
        if (target == null || prefix == null || files == null) {
            return;
        }
        target.deleteDatabase(prefix + "contact-write-journal.db");
        target.deleteSharedPreferences(prefix + "settings");
        for (String name : List.of("activity.log", "activity.log.bak", "activity.log.new")) {
            File file = new File(files, name);
            if (file.exists()) {
                assertTrue(file.delete());
            }
        }
        assertTrue(files.delete());
    }

    /** Creates and appends exactly once while retaining binary fields and private log entries. */
    @Test
    public void createsAndAppendsIdempotently() throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri sourceUri = insertSource(resolver);
        Uri destinationUri = null;
        try {
            long sourceId = ContentUris.parseId(sourceUri);
            GuardedContactWriter.VersionedContact source = version(resolver, sourceId);
            assertEquals(SOURCE_BOOK, source.book());
            GuardedContactWriter writer = new GuardedContactWriter(context);
            String createId = UUID.randomUUID().toString();
            ContentValues name = row(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                    "Synthetic Alice");
            ContentValues note = row(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
                    "Synthetic note");
            long destinationId = writer.create(createId, source, DESTINATION_BOOK, List.of(name, note));
            destinationUri = ContentUris.withAppendedId(
                    ContactsContract.RawContacts.CONTENT_URI, destinationId);
            assertEquals(DESTINATION_BOOK, version(resolver, destinationId).book());
            assertEquals(destinationId, writer.create(createId, source, DESTINATION_BOOK, List.of(name, note)));
            assertThrows(IllegalStateException.class, () -> writer.create(
                    createId, source, DESTINATION_BOOK, List.of(name)));
            assertEquals(1, count(resolver, destinationId,
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE));

            GuardedContactWriter.VersionedContact destination = version(resolver, destinationId);
            ContentValues email = row(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                    "synthetic@example.test");
            email.put(ContactsContract.CommonDataKinds.Email.TYPE,
                    ContactsContract.CommonDataKinds.Email.TYPE_WORK);
            String appendId = UUID.randomUUID().toString();
            assertEquals(destinationId, writer.append(appendId, source, destination, List.of(email)));
            assertEquals(destinationId, writer.append(appendId, source, destination, List.of(email)));
            assertEquals(1, count(resolver, destinationId,
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE));
            assertTrue(new ActivityLog(context).read().contains("Destination write completed " + appendId));
        } finally {
            if (destinationUri != null) {
                resolver.delete(destinationUri, null, null);
            }
            resolver.delete(sourceUri, null, null);
        }
    }

    /** Plans and applies a synthetic missing email, then verifies a repeated plan is a no-op. */
    @Test
    public void plansAndAppliesSyntheticAdditiveSync() throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri sourceUri = insertSource(resolver);
        Uri destinationUri = null;
        try {
            long sourceId = ContentUris.parseId(sourceUri);
            String nameMime = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE;
            String phoneMime = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE;
            String emailMime = ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE;
            addRow(resolver, sourceId, row(nameMime, "Synthetic Sync"));
            addRow(resolver, sourceId, row(phoneMime, "+49 30 12345678"));
            addRow(resolver, sourceId, row(emailMime, "synthetic-sync@example.test"));
            GuardedContactWriter.VersionedContact source = version(resolver, sourceId);
            GuardedContactWriter writer = new GuardedContactWriter(context);
            long destinationId = writer.create(UUID.randomUUID().toString(), source, DESTINATION_BOOK,
                    List.of(row(nameMime, "Synthetic Sync"), row(phoneMime, "+49 30 12345678")));
            destinationUri = ContentUris.withAppendedId(
                    ContactsContract.RawContacts.CONTENT_URI, destinationId);
            Map<String, ContactComparison.Cardinality> fields = Map.of(
                    nameMime, ContactComparison.Cardinality.SINGLE,
                    emailMime, ContactComparison.Cardinality.MULTIPLE);
            var before = ReconciliationPlanner.plan(
                    List.of(plannerContact(resolver, sourceId)),
                    List.of(plannerContact(resolver, destinationId)), List.of(), fields).get(0);
            assertEquals(ReconciliationPlanner.Action.RECONCILE, before.action());
            assertEquals(Set.of(new ContactComparison.Value("synthetic-sync@example.test", "", "")),
                    before.comparison().additions().get(emailMime));
            assertTrue(before.comparison().conflicts().isEmpty());

            // This conversion is deliberately test-only until field-complete mapping exists.
            ContentValues addition = simpleTestRow(emailMime,
                    before.comparison().additions().get(emailMime));
            writer.append(UUID.randomUUID().toString(), source,
                    version(resolver, destinationId), List.of(addition));
            var after = ReconciliationPlanner.plan(
                    List.of(plannerContact(resolver, sourceId)),
                    List.of(plannerContact(resolver, destinationId)), List.of(), fields).get(0);
            assertTrue(after.comparison().additions().isEmpty());
            assertTrue(after.comparison().conflicts().isEmpty());
            assertEquals(1, count(resolver, destinationId, emailMime));
        } finally {
            if (destinationUri != null) {
                resolver.delete(destinationUri, null, null);
            }
            resolver.delete(sourceUri, null, null);
        }
    }

    /** Converts exactly one simple test value into an insertable provider row. */
    private ContentValues simpleTestRow(String mime, Set<ContactComparison.Value> values) {
        assertEquals(1, values.size());
        ContactComparison.Value value = values.iterator().next();
        assertEquals("", value.type());
        assertEquals("", value.label());
        return row(mime, value.value());
    }

    /** Adapts the three synthetic field types used by the integration test. */
    private ReconciliationPlanner.Contact plannerContact(ContentResolver resolver, long id) {
        String nameMime = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE;
        String phoneMime = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE;
        String emailMime = ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE;
        Set<String> phones = new LinkedHashSet<>();
        Map<String, Set<ContactComparison.Value>> fields = new LinkedHashMap<>();
        try (Cursor cursor = resolver.query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1},
                ContactsContract.Data.RAW_CONTACT_ID + " = ?", new String[]{Long.toString(id)}, null)) {
            assertNotNull(cursor);
            while (cursor.moveToNext()) {
                String mime = cursor.getString(0);
                String value = cursor.getString(1);
                if (value == null) {
                    continue;
                }
                if (phoneMime.equals(mime)) {
                    phones.add(value);
                } else if (nameMime.equals(mime) || emailMime.equals(mime)) {
                    fields.computeIfAbsent(mime, ignored -> new LinkedHashSet<>())
                            .add(new ContactComparison.Value(value, "", ""));
                }
            }
        }
        return new ReconciliationPlanner.Contact(Long.toString(id), phones, fields);
    }

    /** Inserts a single test row belonging to a known synthetic raw contact. */
    private void addRow(ContentResolver resolver, long id, ContentValues row) {
        ContentValues data = new ContentValues(row);
        data.put(ContactsContract.Data.RAW_CONTACT_ID, id);
        assertNotNull(resolver.insert(ContactsContract.Data.CONTENT_URI, data));
    }

    /** A changed destination invalidates an append and leaves unrelated fields intact. */
    @Test
    public void staleDestinationDoesNotReceiveAdditions() throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri sourceUri = insertSource(resolver);
        Uri destinationUri = null;
        try {
            GuardedContactWriter writer = new GuardedContactWriter(context);
            GuardedContactWriter.VersionedContact source = version(resolver, ContentUris.parseId(sourceUri));
            long destinationId = writer.create(UUID.randomUUID().toString(), source, DESTINATION_BOOK,
                    List.of(row(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, "original")));
            destinationUri = ContentUris.withAppendedId(
                    ContactsContract.RawContacts.CONTENT_URI, destinationId);
            GuardedContactWriter.VersionedContact stale = version(resolver, destinationId);
            ContentValues local = row(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, "local edit");
            local.put(ContactsContract.Data.RAW_CONTACT_ID, destinationId);
            assertNotNull(resolver.insert(ContactsContract.Data.CONTENT_URI, local));
            String operationId = UUID.randomUUID().toString();
            ContentValues email = row(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                    "not-added@example.test");
            assertThrows(android.content.OperationApplicationException.class,
                    () -> writer.append(operationId, source, stale, List.of(email)));
            assertEquals(0, count(resolver, destinationId,
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE));
            assertEquals(2, count(resolver, destinationId,
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE));
            assertTrue(new ActivityLog(context).read().contains(
                    "Destination write rejected " + operationId));
        } finally {
            if (destinationUri != null) {
                resolver.delete(destinationUri, null, null);
            }
            resolver.delete(sourceUri, null, null);
        }
    }

    /** Photos are transferred as binary and logged by digest, not raw content. */
    @Test
    public void writesBinaryPhotoWithoutDumpingItToLog() throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri sourceUri = insertSource(resolver);
        Uri destinationUri = null;
        try {
            Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.BLUE);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes));
            bitmap.recycle();
            ContentValues photo = new ContentValues();
            photo.put(ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE);
            photo.put(ContactsContract.CommonDataKinds.Photo.PHOTO, bytes.toByteArray());
            GuardedContactWriter.VersionedContact source = version(resolver, ContentUris.parseId(sourceUri));
            String operationId = UUID.randomUUID().toString();
            long id = new GuardedContactWriter(context).create(operationId, source, DESTINATION_BOOK,
                    List.of(row(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                            "Synthetic Photo"), photo));
            destinationUri = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id);
            try (Cursor cursor = resolver.query(ContactsContract.Data.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Photo.PHOTO},
                    ContactsContract.Data.RAW_CONTACT_ID + " = ? AND "
                            + ContactsContract.Data.MIMETYPE + " = ?",
                    new String[]{Long.toString(id),
                            ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE}, null)) {
                assertNotNull(cursor);
                assertTrue(cursor.moveToFirst());
                byte[] saved = cursor.getBlob(0);
                assertNotNull(saved);
                assertNotNull(BitmapFactory.decodeByteArray(saved, 0, saved.length));
            }
            assertTrue(new ActivityLog(context).read().contains("sha256="));
        } finally {
            if (destinationUri != null) {
                resolver.delete(destinationUri, null, null);
            }
            resolver.delete(sourceUri, null, null);
        }
    }

    /** Source changes invalidate a prepared operation and leave it pending, not retried blindly. */
    @Test
    public void staleSourceFailsClosed() throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri sourceUri = insertSource(resolver);
        Uri destinationUri = null;
        try {
            long sourceId = ContentUris.parseId(sourceUri);
            GuardedContactWriter.VersionedContact stale = version(resolver, sourceId);
            ContentValues changed = row(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, "changed");
            changed.put(ContactsContract.Data.RAW_CONTACT_ID, sourceId);
            assertNotNull(resolver.insert(ContactsContract.Data.CONTENT_URI, changed));
            GuardedContactWriter writer = new GuardedContactWriter(context);
            String operationId = UUID.randomUUID().toString();
            List<ContentValues> rows = List.of(row(
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, "Not created"));
            assertThrows(android.content.OperationApplicationException.class,
                    () -> writer.create(operationId, stale, DESTINATION_BOOK, rows));
            assertThrows(android.content.OperationApplicationException.class,
                    () -> writer.create(operationId, stale, DESTINATION_BOOK, rows));
            GuardedContactWriter.VersionedContact current = version(resolver, sourceId);
            long destinationId = writer.create(
                    UUID.randomUUID().toString(), current, DESTINATION_BOOK, rows);
            destinationUri = ContentUris.withAppendedId(
                    ContactsContract.RawContacts.CONTENT_URI, destinationId);
            try (ContactWriteJournal journal = new ContactWriteJournal(context)) {
                assertEquals("failed", journal.find(operationId).state());
            }
        } finally {
            if (destinationUri != null) {
                resolver.delete(destinationUri, null, null);
            }
            resolver.delete(sourceUri, null, null);
        }
    }

    /** Rejects unsupported provider metadata before an operation can be journaled or applied. */
    @Test
    public void rejectsUnsupportedData() throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri sourceUri = insertSource(resolver);
        try {
            GuardedContactWriter writer = new GuardedContactWriter(context);
            GuardedContactWriter.VersionedContact source = version(resolver, ContentUris.parseId(sourceUri));
            assertThrows(IllegalArgumentException.class, () -> writer.create(
                    UUID.randomUUID().toString(), source, source.book(),
                    List.of(row(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, "same book"))));
            ContentValues unsupported = row("application/x-unknown", "bad");
            assertThrows(IllegalArgumentException.class, () -> writer.create(
                    UUID.randomUUID().toString(), source, DESTINATION_BOOK, List.of(unsupported)));
            ContentValues metadata = row(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, "bad");
            metadata.put(ContactsContract.Data.RAW_CONTACT_ID, 42);
            assertThrows(IllegalArgumentException.class, () -> writer.create(
                    UUID.randomUUID().toString(), source, DESTINATION_BOOK, List.of(metadata)));
        } finally {
            resolver.delete(sourceUri, null, null);
        }
    }

    /** A storage failure prevents a destination mutation and does not journal a false success. */
    @Test
    public void logFailurePreventsProviderWrite() throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri sourceUri = insertSource(resolver);
        File invalidDirectory = new File(files, "not-a-directory");
        try {
            try (FileOutputStream stream = new FileOutputStream(invalidDirectory)) {
                stream.write(1);
            }
            Context unavailableLog = new ContextWrapper(context) {
                @Override
                public File getFilesDir() {
                    return invalidDirectory;
                }
            };
            String operationId = UUID.randomUUID().toString();
            String value = "synthetic-should-not-exist-" + operationId;
            GuardedContactWriter.VersionedContact source = version(resolver, ContentUris.parseId(sourceUri));
            assertThrows(IOException.class, () -> new GuardedContactWriter(unavailableLog).create(
                    operationId, source, DESTINATION_BOOK,
                    List.of(row(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, value))));
            try (ContactWriteJournal journal = new ContactWriteJournal(context)) {
                assertEquals(null, journal.find(operationId));
            }
            try (Cursor cursor = resolver.query(ContactsContract.Data.CONTENT_URI,
                    new String[]{ContactsContract.Data._ID},
                    ContactsContract.Data.MIMETYPE + " = ? AND " + ContactsContract.Data.DATA1 + " = ?",
                    new String[]{ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, value}, null)) {
                assertNotNull(cursor);
                assertEquals(0, cursor.getCount());
            }
        } finally {
            resolver.delete(sourceUri, null, null);
            if (invalidDirectory.exists()) {
                assertTrue(invalidDirectory.delete());
            }
        }
    }

    /** Destination writes still use the durable journal when activity logging is disabled. */
    @Test
    public void writesAreJournaledWhenLoggingIsDisabled() throws Exception {
        new SyncSettings(context).setLoggingEnabled(false);
        ContentResolver resolver = context.getContentResolver();
        Uri sourceUri = insertSource(resolver);
        Uri destinationUri = null;
        try {
            String operationId = UUID.randomUUID().toString();
            GuardedContactWriter.VersionedContact source = version(
                    resolver, ContentUris.parseId(sourceUri));
            long destinationId = new GuardedContactWriter(context).create(
                    operationId, source, DESTINATION_BOOK,
                    List.of(row(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
                            "synthetic unlogged write")));
            destinationUri = ContentUris.withAppendedId(
                    ContactsContract.RawContacts.CONTENT_URI, destinationId);
            assertEquals(1, count(resolver, destinationId,
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE));
            assertTrue(!new File(files, "activity.log").exists());
            try (ContactWriteJournal journal = new ContactWriteJournal(context)) {
                assertEquals("complete", journal.find(operationId).state());
            }
        } finally {
            if (destinationUri != null) {
                resolver.delete(destinationUri, null, null);
            }
            resolver.delete(sourceUri, null, null);
        }
    }

    /** Adds one synthetic source in a separate account-scoped book. */
    private Uri insertSource(ContentResolver resolver) {
        ContentValues raw = new ContentValues();
        raw.put(ContactsContract.RawContacts.ACCOUNT_NAME, SOURCE_BOOK.accountName());
        raw.put(ContactsContract.RawContacts.ACCOUNT_TYPE, SOURCE_BOOK.accountType());
        Uri uri = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, raw);
        assertNotNull(uri);
        return uri;
    }

    /** Reads the provider's raw ID, account and current version. */
    private GuardedContactWriter.VersionedContact version(ContentResolver resolver, long id) {
        String[] columns = {ContactsContract.RawContacts._ID, ContactsContract.RawContacts.VERSION,
                ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.DATA_SET};
        try (Cursor cursor = resolver.query(ContentUris.withAppendedId(
                ContactsContract.RawContacts.CONTENT_URI, id), columns, null, null, null)) {
            assertNotNull(cursor);
            assertTrue(cursor.moveToFirst());
            return new GuardedContactWriter.VersionedContact(id,
                    cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.VERSION)),
                    AddressBook.fromCursor(cursor));
        }
    }

    /** Counts matching rows without reading unrelated account data. */
    private int count(ContentResolver resolver, long id, String mime) {
        try (Cursor cursor = resolver.query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.Data._ID},
                ContactsContract.Data.RAW_CONTACT_ID + " = ? AND "
                        + ContactsContract.Data.MIMETYPE + " = ?",
                new String[]{Long.toString(id), mime}, null)) {
            assertNotNull(cursor);
            return cursor.getCount();
        }
    }

    /** Creates an ordinary writable synthetic contact data row. */
    private ContentValues row(String mime, String value) {
        ContentValues values = new ContentValues();
        values.put(ContactsContract.Data.MIMETYPE, mime);
        values.put(ContactsContract.Data.DATA1, value);
        return values;
    }
}
