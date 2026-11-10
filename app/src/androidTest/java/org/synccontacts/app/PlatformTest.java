package org.synccontacts.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.AtomicFile;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.ListenableWorker;
import androidx.work.testing.TestWorkerBuilder;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Exercises private logging and account-scoped scans on an Android device. */
@RunWith(AndroidJUnit4.class)
public final class PlatformTest {
    /** Verifies the activity opens without crashing, including before permissions are granted. */
    @Test
    public void testMainActivityLaunches() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context target = instrumentation.getTargetContext();
        Intent intent = new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(intent);
        try {
            instrumentation.runOnMainSync(() -> assertTrue(containsText(
                    activity.findViewById(android.R.id.content),
                "One-way sync copies")));
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    /** Searches the screen tree for the development-build safety notice. */
    private boolean containsText(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsText(group.getChildAt(i), text)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Round-trips address-book components, including null and empty strings. */
    @Test
    public void testAddressBookSerialization() throws Exception {
        AddressBook original = new AddressBook(null, "", "test-data-set");
        assertEquals(original, AddressBook.decode(original.encode()));
        try {
            AddressBook.decode("{}");
            fail("Incomplete account identifiers must be rejected");
        } catch (org.json.JSONException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /** Checks durable log append, export, and clearing in the test package's storage. */
    @Test
    public void testPrivateLog() throws Exception {
        Context context = isolatedContext();
        ActivityLog log = new ActivityLog(context);
        log.clear();
        log.append("synthetic event");
        assertTrue(log.read().contains("synthetic event"));
        File export = log.export();
        assertTrue(export.exists());
        File secondExport = log.export();
        assertFalse(export.equals(secondExport));
        log.clear();
        assertFalse(export.exists());
        assertFalse(secondExport.exists());
        assertFalse(log.read().contains("synthetic event"));
        assertTrue(log.read().contains("Log cleared by user"));
    }

    /** Inserts and removes only one synthetic raw contact, then checks its snapshot. */
    @Test
    public void testSourceSnapshot() throws Exception {
        ContentResolver resolver = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getContentResolver();
        ContentValues raw = new ContentValues();
        raw.putNull(ContactsContract.RawContacts.ACCOUNT_NAME);
        raw.putNull(ContactsContract.RawContacts.ACCOUNT_TYPE);
        Uri inserted = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, raw);
        assertNotNull(inserted);
        long id = ContentUris.parseId(inserted);
        try {
            ContentValues phone = new ContentValues();
            phone.put(ContactsContract.Data.RAW_CONTACT_ID, id);
            phone.put(ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);
            phone.put(ContactsContract.CommonDataKinds.Phone.NUMBER, "+491234567890");
            assertNotNull(resolver.insert(ContactsContract.Data.CONTENT_URI, phone));
            AddressBook book = readBook(resolver, id);
            JSONObject snapshot = new ContactSnapshot(resolver).read(book);
            assertTrue(snapshot.has(Long.toString(id)));
            assertTrue(snapshot.getJSONArray(Long.toString(id)).toString().contains("+491234567890"));
        } finally {
            resolver.delete(inserted, null, null);
        }
    }

    /** Reads the actual account chosen by the provider for the synthetic contact. */
    private AddressBook readBook(ContentResolver resolver, long id) {
        String[] columns = {ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.DATA_SET};
        try (Cursor cursor = resolver.query(ContentUris.withAppendedId(
                ContactsContract.RawContacts.CONTENT_URI, id), columns, null, null, null)) {
            assertNotNull(cursor);
            assertTrue(cursor.moveToFirst());
            return AddressBook.fromCursor(cursor);
        }
    }

    /** Verifies snapshots are persisted separately for each selected pair. */
    @Test
    public void testPairSpecificSnapshots() throws Exception {
        Context context = isolatedContext();
        AddressBook source = new AddressBook("source", "test", null);
        AddressBook first = new AddressBook("first", "test", null);
        AddressBook second = new AddressBook("second", "test", null);
        AtomicFile left = ContactSnapshot.file(context.getFilesDir(), source, first);
        AtomicFile right = ContactSnapshot.file(context.getFilesDir(), source, second);
        assertFalse(left.getBaseFile().equals(right.getBaseFile()));
        JSONObject data = new JSONObject().put("123", new org.json.JSONArray().put("test"));
        ContactSnapshot.save(left, data);
        assertEquals(data.toString(), ContactSnapshot.load(left).toString());
        left.delete();
    }

    /** A backup is recovered; an orphaned incomplete write is not treated as an empty file. */
    @Test
    public void testInterruptedAtomicWrites() throws Exception {
        File base = new File(isolatedContext().getFilesDir(), "atomic-test");
        AtomicFile file = new AtomicFile(base);
        file.delete();
        File backup = new File(base.getPath() + ".bak");
        try {
            try (FileOutputStream stream = new FileOutputStream(backup)) {
                stream.write("saved".getBytes(StandardCharsets.UTF_8));
            }
            assertEquals("saved", new String(AtomicFiles.readIfPresent(file), StandardCharsets.UTF_8));
            file.delete();
            File pending = new File(base.getPath() + ".new");
            try (FileOutputStream stream = new FileOutputStream(pending)) {
                stream.write("partial".getBytes(StandardCharsets.UTF_8));
            }
            try {
                AtomicFiles.readIfPresent(file);
                fail("Incomplete initial write must be reported");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("Incomplete atomic write"));
            }
        } finally {
            file.delete();
        }
    }

    /** Exercises source observations and guarded destination synchronization on a synthetic account. */
    @Test
    public void testWorkerObservesSourceChanges() throws Exception {
        Context context = isolatedContext();
        ContentResolver resolver = context.getContentResolver();
        AddressBook source = new AddressBook("sync-source", "org.synccontacts.synthetic", null);
        Uri anchor = insertRawContact(resolver, source);
        Uri contact = insertRawContact(resolver, source);
        AtomicFile snapshot = null;
        try {
            long id = ContentUris.parseId(contact);
            assertEquals(source, readBook(resolver, ContentUris.parseId(anchor)));
            AddressBook destination = new AddressBook(null, null, null);
            SyncSettings settings = new SyncSettings(context);
            settings.select(source, destination);
            snapshot = ContactSnapshot.file(context.getFilesDir(), source, destination);
            snapshot.delete();
            ActivityLog log = new ActivityLog(context);
            log.clear();

            ContentValues phone = new ContentValues();
            phone.put(ContactsContract.Data.RAW_CONTACT_ID, id);
            phone.put(ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);
            phone.put(ContactsContract.CommonDataKinds.Phone.NUMBER, "+491234567890");
            Uri row = resolver.insert(ContactsContract.Data.CONTENT_URI, phone);
            assertNotNull(row);
            assertEquals(ListenableWorker.Result.success(), worker(context).doWork());
            assertTrue(log.read().contains("initial discovery source raw contact " + id));

            ContentValues changed = new ContentValues();
            changed.put(ContactsContract.CommonDataKinds.Phone.NUMBER, "+491234567891");
            assertEquals(1, resolver.update(row, changed, null, null));
            assertEquals(ListenableWorker.Result.success(), worker(context).doWork());
            assertTrue(log.read().contains("modified source raw contact " + id));
            assertTrue(log.read().contains("+491234567891"));

            assertEquals(1, resolver.delete(contact, null, null));
            contact = null;
            assertEquals(ListenableWorker.Result.success(), worker(context).doWork());
            assertTrue(log.read().contains("deleted source raw contact " + id));
        } finally {
            if (contact != null) {
                resolver.delete(contact, null, null);
            }
            resolver.delete(anchor, null, null);
            if (snapshot != null) {
                snapshot.delete();
            }
            context.deleteSharedPreferences("settings");
        }
    }

    /** Exercises actual create, additive update, repeat, and linked-source deletion sync. */
    @Test
    public void testEndToEndSyntheticSync() throws Exception {
        Context context = isolatedContext();
        ContentResolver resolver = context.getContentResolver();
        ContentValues raw = new ContentValues();
        raw.put(ContactsContract.RawContacts.ACCOUNT_NAME, "engine-source");
        raw.put(ContactsContract.RawContacts.ACCOUNT_TYPE, "org.synccontacts.engine-test");
        Uri source = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, raw);
        assertNotNull(source);
        long sourceId = ContentUris.parseId(source);
        AddressBook sourceBook = readBook(resolver, sourceId);
        AddressBook destinationBook = new AddressBook(null, null, null);
        Uri destination = null;
        try {
            insertValue(resolver, sourceId,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                    "Engine Synthetic");
            insertValue(resolver, sourceId,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, "+49 30 12345678");
            insertValue(resolver, sourceId,
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, "engine@example.test");
            new SyncSettings(context).select(sourceBook, destinationBook);
            ListenableWorker.Result firstRun = SyncEngine.run(context, () -> false);
            assertEquals(new SyncSettings(context).status() + "\n" + new ActivityLog(context).read(),
                    ListenableWorker.Result.success(), firstRun);
            destination = findDestination(resolver, "engine@example.test");
            assertNotNull(destination);
            long destinationId = ContentUris.parseId(destination);
            assertEquals(1, count(resolver, destinationId,
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE));

            assertEquals(androidx.work.ListenableWorker.Result.success(),
                    SyncEngine.run(context, () -> false));
            assertEquals(1, count(resolver, destinationId,
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE));
            insertValue(resolver, sourceId,
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, "Added later");
            assertEquals(androidx.work.ListenableWorker.Result.success(),
                    SyncEngine.run(context, () -> false));
            assertEquals(1, count(resolver, destinationId,
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE));

            assertEquals(1, resolver.delete(source, null, null));
            source = null;
            assertEquals(androidx.work.ListenableWorker.Result.success(),
                    SyncEngine.run(context, () -> false));
            try (Cursor cursor = resolver.query(ContactsContract.RawContacts.CONTENT_URI,
                    new String[]{ContactsContract.RawContacts._ID},
                    ContactsContract.RawContacts._ID + " = ? AND "
                            + ContactsContract.RawContacts.DELETED + " = 0",
                    new String[]{Long.toString(destinationId)}, null)) {
                assertNotNull(cursor);
                assertEquals(new ActivityLog(context).read(), 0, cursor.getCount());
            }
            assertTrue(new ActivityLog(context).read().contains("Deleted destination raw contact"));
        } finally {
            if (source != null) {
                resolver.delete(source, null, null);
            }
            if (destination != null) {
                resolver.delete(destination, null, null);
            }
            cleanupIsolatedContext(context);
        }
    }

    /** Matches an existing destination, preserves its extras, and resolves a field conflict. */
    @Test
    public void testInitialMatchConflictAndUserResolution() throws Exception {
        Context context = isolatedContext();
        ContentResolver resolver = context.getContentResolver();
        ContentValues raw = new ContentValues();
        raw.put(ContactsContract.RawContacts.ACCOUNT_NAME, "match-source");
        raw.put(ContactsContract.RawContacts.ACCOUNT_TYPE, "org.synccontacts.match-test");
        Uri source = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, raw);
        assertNotNull(source);
        long sourceId = ContentUris.parseId(source);
        AddressBook sourceBook = readBook(resolver, sourceId);
        Uri destination = insertLocalRawContact(resolver);
        long destinationId = ContentUris.parseId(destination);
        try {
            insertValue(resolver, sourceId,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, "Source Name");
            insertValue(resolver, sourceId,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, "+49 30 12345678");
            insertValue(resolver, sourceId,
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, "match@example.test");
            insertValue(resolver, destinationId,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, "Destination Name");
            insertValue(resolver, destinationId,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, "0049 (30) 12345678");
            insertValue(resolver, destinationId,
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, "Keep destination note");
            AddressBook destinationBook = readBook(resolver, destinationId);
            assertNotEquals(sourceBook, destinationBook);
            ProviderContact sourceData = ProviderContact.read(resolver, sourceId, sourceBook);
            ProviderContact destinationData = ProviderContact.read(resolver, destinationId, destinationBook);
            assertEquals(sourceData.fields().get(ProviderContact.PHONE),
                    destinationData.fields().get(ProviderContact.PHONE));
            new SyncSettings(context).select(sourceBook, destinationBook);

            ListenableWorker.Result firstRun = SyncEngine.run(context, () -> false);
            assertEquals(new SyncSettings(context).status() + "\n" + new ActivityLog(context).read(),
                    ListenableWorker.Result.success(), firstRun);
            assertEquals(1, count(resolver, destinationId,
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE));
            assertEquals(1, count(resolver, destinationId,
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE));
            String pair = SyncStateStore.pairKey(sourceBook, destinationBook);
            SyncConflict conflict;
            try (SyncStateStore state = new SyncStateStore(context)) {
                assertEquals(1, state.links(pair).size());
                assertEquals(Long.toString(destinationId), state.links(pair).get(0).destinationId());
                assertEquals(1, state.conflicts(pair).size());
                conflict = state.conflicts(pair).get(0);
            }
            SyncEngine.resolveConflict(context, conflict, true);
            assertEquals("Source Name", readFirstValue(resolver, destinationId,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE));
            assertEquals(1, count(resolver, destinationId,
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE));
            assertEquals(androidx.work.ListenableWorker.Result.success(),
                    SyncEngine.run(context, () -> false));
            assertEquals(1, count(resolver, destinationId,
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE));
        } finally {
            resolver.delete(source, null, null);
            resolver.delete(destination, null, null);
            cleanupIsolatedContext(context);
        }
    }

    /** Creates one synthetic device-local destination raw contact. */
    private Uri insertLocalRawContact(ContentResolver resolver) {
        ContentValues raw = new ContentValues();
        raw.putNull(ContactsContract.RawContacts.ACCOUNT_NAME);
        raw.putNull(ContactsContract.RawContacts.ACCOUNT_TYPE);
        Uri contact = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, raw);
        assertNotNull(contact);
        return contact;
    }

    /** Reads one data1 value for an exact raw contact and MIME type. */
    private String readFirstValue(ContentResolver resolver, long id, String mime) {
        try (Cursor cursor = resolver.query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.Data.DATA1},
                ContactsContract.Data.RAW_CONTACT_ID + " = ? AND "
                        + ContactsContract.Data.MIMETYPE + " = ?",
                new String[]{Long.toString(id), mime}, null)) {
            assertNotNull(cursor);
            assertTrue(cursor.moveToFirst());
            return cursor.getString(0);
        }
    }

    /** Inserts a supported synthetic field into one raw contact. */
    private void insertValue(ContentResolver resolver, long id, String mime, String value) {
        ContentValues row = new ContentValues();
        row.put(ContactsContract.Data.RAW_CONTACT_ID, id);
        row.put(ContactsContract.Data.MIMETYPE, mime);
        row.put(ContactsContract.Data.DATA1, value);
        if (ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE.equals(mime)) {
            row.put(ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE);
        }
        assertNotNull(resolver.insert(ContactsContract.Data.CONTENT_URI, row));
    }

    /** Finds the synthetic destination by its unique test email value. */
    private Uri findDestination(ContentResolver resolver, String email) {
        try (Cursor cursor = resolver.query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.Data.RAW_CONTACT_ID},
                ContactsContract.Data.MIMETYPE + " = ? AND " + ContactsContract.Data.DATA1 + " = ?",
                new String[]{ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, email}, null)) {
            assertNotNull(cursor);
            assertTrue(cursor.moveToFirst());
            return ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI,
                    cursor.getLong(0));
        }
    }

    /** Counts rows of one MIME type in a single synthetic raw contact. */
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

    /** Removes only files and databases created by the wrapped test context. */
    private void cleanupIsolatedContext(Context context) {
        context.deleteSharedPreferences("settings");
        context.deleteDatabase("sync-state.db");
        context.deleteDatabase("contact-write-journal.db");
        for (File file : context.getFilesDir().listFiles()) {
            assertTrue(file.delete());
        }
        assertTrue(context.getFilesDir().delete());
        for (File file : context.getCacheDir().listFiles()) {
            assertTrue(file.delete());
        }
        assertTrue(context.getCacheDir().delete());
    }

    /** An unavailable source is a retryable error, never a successful empty scan. */
    @Test
    public void testUnavailableSourceIsNotTreatedAsDeletion() throws Exception {
        Context context = isolatedContext();
        AddressBook source = new AddressBook("missing-source-for-test", "invalid-type", null);
        AddressBook destination = new AddressBook("destination-for-test", "invalid-type", null);
        AtomicFile snapshot = ContactSnapshot.file(context.getFilesDir(), source, destination);
        try {
            new SyncSettings(context).select(source, destination);
            JSONObject previous = new JSONObject().put("123", new org.json.JSONArray().put("old"));
            ContactSnapshot.save(snapshot, previous);
            assertEquals(ListenableWorker.Result.retry(), worker(context).doWork());
            assertEquals(previous.toString(), ContactSnapshot.load(snapshot).toString());
            assertTrue(new SyncSettings(context).status().contains(
                    "Source address book account is unavailable"));
        } finally {
            snapshot.delete();
            context.deleteSharedPreferences("settings");
        }
    }

    /** Keeps the device-local book selectable even when it has no raw contacts. */
    @Test
    public void testDiscoveryIncludesEmptyDeviceLocalBook() {
        Context context = isolatedContext();
        assertTrue(ContactBooks.discover(context).contains(new AddressBook(null, null, null)));
    }

    /** Creates one explicitly addressed synthetic local raw contact. */
    private Uri insertRawContact(ContentResolver resolver) {
        ContentValues raw = new ContentValues();
        raw.putNull(ContactsContract.RawContacts.ACCOUNT_NAME);
        raw.putNull(ContactsContract.RawContacts.ACCOUNT_TYPE);
        Uri contact = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, raw);
        assertNotNull(contact);
        return contact;
    }

    /** Inserts a raw contact into the requested synthetic account for an isolated sync test. */
    private Uri insertRawContact(ContentResolver resolver, AddressBook book) {
        ContentValues raw = new ContentValues();
        raw.put(ContactsContract.RawContacts.ACCOUNT_NAME, book.accountName());
        raw.put(ContactsContract.RawContacts.ACCOUNT_TYPE, book.accountType());
        if (book.dataSet() == null) {
            raw.putNull(ContactsContract.RawContacts.DATA_SET);
        } else {
            raw.put(ContactsContract.RawContacts.DATA_SET, book.dataSet());
        }
        Uri inserted = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, raw);
        assertNotNull(inserted);
        return inserted;
    }

    /** Builds a worker with isolated settings and synchronous test execution. */
    private MonitorWorker worker(Context context) {
        return TestWorkerBuilder.from(context, MonitorWorker.class, Runnable::run).build();
    }

    /** Keeps test logs and snapshots separate from the application's real files. */
    private Context isolatedContext() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix = "platform-test-" + UUID.randomUUID() + "-";
        File root = new File(target.getCacheDir(), prefix);
        File files = new File(root, "files");
        assertTrue(files.isDirectory() || files.mkdirs());
        return new ContextWrapper(target) {
            @Override
            public Context getApplicationContext() {
                return this;
            }

            @Override
            public File getFilesDir() {
                return files;
            }

            @Override
            public File getCacheDir() {
                return root;
            }

            @Override
            public File getDatabasePath(String name) {
                return target.getDatabasePath(prefix + name);
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

            @Override
            public boolean deleteDatabase(String name) {
                return target.deleteDatabase(prefix + name);
            }

            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                return target.getSharedPreferences(prefix + name, mode);
            }

            @Override
            public boolean deleteSharedPreferences(String name) {
                return target.deleteSharedPreferences(prefix + name);
            }
        };
    }
}
