package org.synccontacts.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
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
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.ListenableWorker;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.TestWorkerBuilder;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
                "One-way sync copies") || containsText(
                        activity.findViewById(android.R.id.content), "Settings")));
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

    /** Finds a control by its exact label for activity navigation checks. */
    private View findText(View view, String label) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(label)) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Verifies first-run setup, settings log controls, and the configured main screen. */
    @Test
    public void testFirstRunAndSettingsNavigation() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var automation = instrumentation.getUiAutomation();
        Context target = instrumentation.getTargetContext();
        SharedPreferences preferences = target.getSharedPreferences("settings", Context.MODE_PRIVATE);
        Map<String, ?> original = preferences.getAll();
        String[] keys = {"source", "destination", "generation", "status",
                "background_sync_enabled", "logging_enabled", "sync_interval_minutes"};
        AddressBook source = new AddressBook("ui-" + UUID.randomUUID(),
                "org.synccontacts.synthetic", null);
        Uri anchor = insertRawContact(target.getContentResolver(), source);
        MainActivity activity = null;
        try {
            assertTrue(preferences.edit().remove("source").remove("destination")
                    .remove("background_sync_enabled")
                    .remove("sync_interval_minutes")
                    .remove("logging_enabled").commit());
            Intent intent = new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (MainActivity) instrumentation.startActivitySync(intent);
            MainActivity setup = activity;
            instrumentation.waitForIdleSync();
            target.getContentResolver().notifyChange(
                    ContactsContract.RawContacts.CONTENT_URI, null);
            instrumentation.waitForIdleSync();
            WorkManager manager = WorkManager.getInstance(target);
            for (String name : new String[]{MonitorScheduler.PERIODIC, MonitorScheduler.IMMEDIATE}) {
                for (WorkInfo work : manager.getWorkInfosForUniqueWork(name).get()) {
                    assertTrue("Unexpected automatic work before setup: " + name,
                            work.getState().isFinished());
                }
            }
            instrumentation.runOnMainSync(() -> {
                View root = setup.findViewById(android.R.id.content);
                assertNotNull(findText(root, "Settings"));
                assertNotNull(findText(root, "From"));
                assertNotNull(findText(root, "To"));
                assertTrue(((CheckBox) findText(root, "Enable background sync")).isChecked());
                View intervalLabel = findText(root, "Background sync interval");
                assertNotNull(intervalLabel);
                ViewGroup parent = (ViewGroup) intervalLabel.getParent();
                Spinner interval = (Spinner) parent.getChildAt(parent.indexOfChild(intervalLabel) + 1);
                assertEquals(7, interval.getCount());
                assertEquals(SyncInterval.MINUTES_15, interval.getSelectedItem());
                for (int i = 0; i < SyncInterval.values().length; i++) {
                    assertEquals(SyncInterval.values()[i], interval.getItemAtPosition(i));
                }
                assertNull(findText(root, "Enable logging"));
                View cancel = findText(root, "Cancel");
                View logButton = findText(root, "Logging...");
                assertNotNull(cancel);
                assertNotNull(logButton);
                assertTrue(cancel.getHeight() > 0);
                assertEquals(cancel.getHeight(), logButton.getTop() - cancel.getBottom());
                findText(root, "Logging...").performClick();
                root = setup.findViewById(android.R.id.content);
                assertNotNull(findText(root, "Open log"));
                CheckBox logging = (CheckBox) findText(root, "Enable logging");
                assertNotNull(logging);
                assertFalse(logging.isChecked());
                logging.performClick();
                assertTrue(new SyncSettings(target).loggingEnabled());
                findText(setup.findViewById(android.R.id.content), "Back").performClick();
                assertNull(findText(setup.findViewById(android.R.id.content), "Enable logging"));
                findText(setup.findViewById(android.R.id.content), "Logging...").performClick();
                assertTrue(((CheckBox) findText(setup.findViewById(android.R.id.content),
                        "Enable logging")).isChecked());
                setup.finish();
            });
            activity = null;
            new SyncSettings(target).setBackgroundSyncEnabled(false);
            new SyncSettings(target).select(source, new AddressBook(null, null, null));
            activity = (MainActivity) instrumentation.startActivitySync(intent);
            MainActivity configured = activity;
            instrumentation.runOnMainSync(() -> {
                View root = configured.findViewById(android.R.id.content);
                assertNotNull(findText(root, "Force sync now"));
                assertNull(findText(root, "Logging..."));
                findText(root, "Settings").performClick();
                root = configured.findViewById(android.R.id.content);
                View label = findText(root, "Background sync interval");
                ViewGroup parent = (ViewGroup) label.getParent();
                Spinner interval = (Spinner) parent.getChildAt(parent.indexOfChild(label) + 1);
                interval.setSelection(SyncInterval.HOURS_24.ordinal());
                findText(root, "Save settings").performClick();
            });
            instrumentation.waitForIdleSync();
            AccessibilityNodeInfo window = automation.getRootInActiveWindow();
            assertNotNull(window);
            var confirms = window.findAccessibilityNodeInfosByText("Confirm");
            AccessibilityNodeInfo confirm = null;
            for (AccessibilityNodeInfo node : confirms) {
                if (node.getText() != null
                        && "Confirm".equalsIgnoreCase(node.getText().toString())
                        && node.isClickable()) {
                    confirm = node;
                    break;
                }
            }
            assertNotNull("Confirm button not found in " + confirms, confirm);
            assertTrue(confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            instrumentation.waitForIdleSync();
            assertEquals(SyncInterval.HOURS_24, new SyncSettings(target).syncInterval());
            assertFalse(new SyncSettings(target).backgroundSyncEnabled());
            instrumentation.runOnMainSync(() -> {
                View root = configured.findViewById(android.R.id.content);
                findText(root, "Settings").performClick();
                root = configured.findViewById(android.R.id.content);
                View label = findText(root, "Background sync interval");
                ViewGroup parent = (ViewGroup) label.getParent();
                Spinner interval = (Spinner) parent.getChildAt(parent.indexOfChild(label) + 1);
                assertEquals(SyncInterval.HOURS_24, interval.getSelectedItem());
                interval.setSelection(SyncInterval.MINUTES_30.ordinal());
                findText(root, "Cancel").performClick();
            });
            assertEquals(SyncInterval.HOURS_24, new SyncSettings(target).syncInterval());
        } finally {
            if (activity != null) {
                MainActivity toFinish = activity;
                instrumentation.runOnMainSync(toFinish::finish);
            }
            SharedPreferences.Editor restore = preferences.edit();
            for (String key : keys) {
                Object value = original.get(key);
                if (value instanceof String) {
                    restore.putString(key, (String) value);
                } else if (value instanceof Boolean) {
                    restore.putBoolean(key, (Boolean) value);
                } else if (value instanceof Long) {
                    restore.putLong(key, (Long) value);
                } else if (value instanceof Integer) {
                    restore.putInt(key, (Integer) value);
                } else {
                    restore.remove(key);
                }
            }
            assertTrue(restore.commit());
            target.getContentResolver().delete(anchor, null, null);
        }
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
        new SyncSettings(context).setLoggingEnabled(true);
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

    /** Disabling logging stops new entries without erasing existing private log data. */
    @Test
    public void testLoggingIsOptInAndPreservesEarlierEntries() throws Exception {
        Context context = isolatedContext();
        try {
            SyncSettings settings = new SyncSettings(context);
            ActivityLog log = new ActivityLog(context);
            assertFalse(settings.loggingEnabled());
            log.append("not recorded");
            assertEquals("", log.read());
            assertFalse(new File(context.getFilesDir(), "activity.log").exists());
            settings.setLoggingEnabled(true);
            log.append("recorded");
            assertTrue(log.read().contains("recorded"));
            settings.setLoggingEnabled(false);
            log.appendAll(java.util.List.of("not recorded after disabling"));
            assertTrue(log.read().contains("recorded"));
            assertFalse(log.read().contains("not recorded"));
            log.clear();
            assertEquals("", log.read());
        } finally {
            cleanupIsolatedContext(context);
        }
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
            settings.setLoggingEnabled(true);
            settings.select(source, destination);
            settings.setBackgroundSyncEnabled(true);
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
        String email = "engine-" + UUID.randomUUID() + "@example.test";
        Uri destination = null;
        try {
            new SyncSettings(context).setLoggingEnabled(true);
            insertValue(resolver, sourceId,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                    "Engine Synthetic");
            insertValue(resolver, sourceId,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, "+49 30 12345678");
            insertValue(resolver, sourceId,
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, email);
            new SyncSettings(context).select(sourceBook, destinationBook);
            new SyncSettings(context).setBackgroundSyncEnabled(false);
            assertFalse(new SyncSettings(context).backgroundSyncEnabled());
            assertEquals(ListenableWorker.Result.success(),
                    SyncEngine.run(context, () -> false));
            assertNull(findDestination(resolver, email, destinationBook));
            SyncEngine.RunReport firstReport = SyncEngine.runManual(context, () -> false);
            assertTrue(firstReport.success());
            assertTrue(firstReport.syncedItems().contains("Engine Synthetic"));
            ListenableWorker.Result firstRun = firstReport.result();
            assertEquals(new SyncSettings(context).status() + "\n" + new ActivityLog(context).read(),
                    ListenableWorker.Result.success(), firstRun);
            destination = findDestination(resolver, email, destinationBook);
            assertNotNull(destination);
            long destinationId = ContentUris.parseId(destination);
            assertEquals(1, count(resolver, destinationId,
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE));

            assertEquals(androidx.work.ListenableWorker.Result.success(),
                    SyncEngine.runManual(context, () -> false).result());
            assertEquals(1, count(resolver, destinationId,
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE));
            insertValue(resolver, sourceId,
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, "Added later");
            assertEquals(androidx.work.ListenableWorker.Result.success(),
                    SyncEngine.runManual(context, () -> false).result());
            assertEquals(1, count(resolver, destinationId,
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE));

            assertEquals(1, resolver.delete(source, null, null));
            source = null;
            assertEquals(androidx.work.ListenableWorker.Result.success(),
                    SyncEngine.runManual(context, () -> false).result());
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

            ListenableWorker.Result firstRun =
                    SyncEngine.runManual(context, () -> false).result();
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
                    SyncEngine.runManual(context, () -> false).result());
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
    private Uri findDestination(ContentResolver resolver, String email, AddressBook expectedBook) {
        try (Cursor cursor = resolver.query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.Data.RAW_CONTACT_ID},
                ContactsContract.Data.MIMETYPE + " = ? AND " + ContactsContract.Data.DATA1 + " = ?",
                new String[]{ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, email}, null)) {
            assertNotNull(cursor);
            while (cursor.moveToNext()) {
                long rawId = cursor.getLong(0);
                if (expectedBook.equals(readBook(resolver, rawId))) {
                    return ContentUris.withAppendedId(
                            ContactsContract.RawContacts.CONTENT_URI, rawId);
                }
            }
            return null;
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
            new SyncSettings(context).setBackgroundSyncEnabled(true);
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

    /** Enables background sync by default and preserves a user's explicit choice. */
    @Test
    public void testBackgroundSyncDefaultsEnabled() throws Exception {
        Context context = isolatedContext();
        try {
            SyncSettings settings = new SyncSettings(context);
            assertTrue(settings.backgroundSyncEnabled());
            settings.select(new AddressBook("source", "test", null),
                    new AddressBook("destination", "test", null));
            assertTrue(settings.backgroundSyncEnabled());
            settings.setBackgroundSyncEnabled(false);
            assertFalse(settings.backgroundSyncEnabled());
            assertFalse(new SyncSettings(context).backgroundSyncEnabled());
            settings.setBackgroundSyncEnabled(true);
            assertTrue(settings.backgroundSyncEnabled());
        } finally {
            cleanupIsolatedContext(context);
        }
    }

    /** Verifies every persisted choice and the default for an existing installation. */
    @Test
    public void testSyncIntervalsPersist() {
        Context context = isolatedContext();
        try {
            SyncSettings settings = new SyncSettings(context);
            assertEquals(SyncInterval.MINUTES_15, settings.syncInterval());
            for (SyncInterval interval : SyncInterval.values()) {
                settings.setSyncInterval(interval);
                assertEquals(interval, new SyncSettings(context).syncInterval());
            }
            try {
                SyncInterval.fromMinutes(5);
                fail("Unsupported intervals must be rejected");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("5"));
            }
        } finally {
            cleanupIsolatedContext(context);
        }
    }

    /** Verifies the actual unique WorkManager schedule is updated for every interval. */
    @Test
    public void testConfiguredPeriodicScheduleUpdates() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        Map<String, ?> original = preferences.getAll();
        WorkManager manager = WorkManager.getInstance(context);
        try {
            assertTrue(preferences.edit().putBoolean("background_sync_enabled", false).commit());
            manager.cancelUniqueWork(MonitorScheduler.PERIODIC).getResult().get();
            UUID workId = null;
            for (SyncInterval interval : SyncInterval.values()) {
                new SyncSettings(context).setSyncInterval(interval);
                MonitorScheduler.start(context);
                WorkInfo active = null;
                for (WorkInfo work : manager.getWorkInfosForUniqueWork(MonitorScheduler.PERIODIC).get()) {
                    if (!work.getState().isFinished()) {
                        assertNull("Only one periodic schedule should be active", active);
                        active = work;
                    }
                }
                assertNotNull(active);
                assertNotNull(active.getPeriodicityInfo());
                assertEquals(TimeUnit.MINUTES.toMillis(interval.minutes()),
                        active.getPeriodicityInfo().getRepeatIntervalMillis());
                if (workId != null) {
                    assertEquals(workId, active.getId());
                }
                workId = active.getId();
            }
        } finally {
            manager.cancelUniqueWork(MonitorScheduler.PERIODIC).getResult().get();
            SharedPreferences.Editor restore = preferences.edit();
            Object enabled = original.get("background_sync_enabled");
            if (enabled instanceof Boolean) {
                restore.putBoolean("background_sync_enabled", (Boolean) enabled);
            } else {
                restore.remove("background_sync_enabled");
            }
            Object interval = original.get("sync_interval_minutes");
            if (interval instanceof Integer) {
                restore.putInt("sync_interval_minutes", (Integer) interval);
            } else {
                restore.remove("sync_interval_minutes");
            }
            assertTrue(restore.commit());
            SyncSettings settings = new SyncSettings(context);
            if (settings.backgroundSyncEnabled() && settings.source() != null
                    && settings.destination() != null
                    && !settings.source().equals(settings.destination())) {
                MonitorScheduler.start(context);
            }
        }
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
