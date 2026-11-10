package org.synccontacts.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.json.JSONException;

/** Provides setup and log access while destination synchronization is unfinished. */
public final class MainActivity extends Activity {
    private static final int CONTACT_PERMISSION_REQUEST = 1;
    private LinearLayout content;
    private boolean observerRegistered;
    private final ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
        /** Schedules a check when the provider changes while the screen is active. */
        @Override
        public void onChange(boolean selfChange) {
            if (new SyncSettings(MainActivity.this).enabled()) {
                MonitorScheduler.request(MainActivity.this);
            }
        }
    };

    /** Starts the status screen and reconciles the persistent schedule. */
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SyncSettings settings = new SyncSettings(this);
        if (settings.enabled()) {
            MonitorScheduler.start(this);
        }
        showMain();
    }

    /** Registers provider notifications only while the app is visible. */
    @Override
    protected void onStart() {
        super.onStart();
        registerObserver();
    }

    /** Removes the transient observer; periodic work remains scheduled. */
    @Override
    protected void onStop() {
        if (observerRegistered) {
            getContentResolver().unregisterContentObserver(observer);
            observerRegistered = false;
        }
        super.onStop();
    }

    /** Opens the setup screen after both permissions are granted. */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CONTACT_PERMISSION_REQUEST) {
            if (hasPermissions()) {
                registerObserver();
                showSettings();
            } else {
                new AlertDialog.Builder(this).setTitle("Contact access required")
                        .setMessage("Allow read and write contact permissions to configure address books. "
                                + "You can enable them in Android app settings.")
                        .setPositiveButton("App settings", (dialog, which) -> openSystemSettings())
                        .setNegativeButton("Cancel", null).show();
            }
        }
    }

    /** Displays sync state and any individual decisions waiting for the user. */
    private void showMain() {
        resetView("SyncContacts");
        text("One-way sync copies source details to the destination. Deleting a source "
                + "contact deletes its linked destination contact and destination-only details.");
        try {
            SyncSettings settings = new SyncSettings(this);
            AddressBook source = settings.source();
            AddressBook destination = settings.destination();
            text(source == null ? "From: not selected" : "From: " + source.label());
            text(destination == null ? "To: not selected" : "To: " + destination.label());
            text("Monitoring: " + (settings.enabled() ? "enabled" : "paused or not configured"));
            text(settings.status());
            text("Checks are scheduled about every four hours. Android may delay work; "
                    + "Force stop prevents it until the app is reopened.");
            int pending = pendingCount(settings, source, destination);
            text("Pending review items: " + pending);
            button("Settings", this::requestSettings);
            button("Log", this::showLog);
            if (source != null && destination != null) {
                if (pending > 0) {
                    button("Review decisions", this::showDecisions);
                }
                button(settings.enabled() ? "Pause monitoring" : "Resume monitoring",
                        this::toggleMonitoring);
                button("Check now", () -> {
                    if (!new SyncSettings(this).enabled()) {
                        message("Monitoring is paused.");
                        return;
                    }
                    MonitorScheduler.request(this);
                    message("Sync check requested; it has not necessarily completed.");
                });
            }
            button("Refresh status", this::showMain);
        } catch (JSONException | IllegalStateException error) {
            showError("Could not read settings", error);
        }
    }

    /** Counts unresolved matches and field conflicts for the current direction. */
    private int pendingCount(SyncSettings settings, AddressBook source, AddressBook destination)
            throws JSONException {
        if (source == null || destination == null) {
            return 0;
        }
        try (SyncStateStore state = new SyncStateStore(this)) {
            String pair = SyncStateStore.pairKey(source, destination);
            return state.matches(pair).size() + state.conflicts(pair).size();
        }
    }

    /** Explains contact access and the eventual destructive sync behavior first. */
    private void requestSettings() {
        if (hasPermissions()) {
            showSettings();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Contact access")
                .setMessage("SyncContacts needs contact access for one-way synchronization. "
                        + "The planned sync can delete a linked destination contact, including "
                        + "one that already existed. This app can write to the selected destination.")
                .setPositiveButton("Continue", (dialog, which) -> requestPermissions(
                        new String[]{Manifest.permission.READ_CONTACTS,
                                Manifest.permission.WRITE_CONTACTS}, CONTACT_PERMISSION_REQUEST))
                .setNegativeButton("Cancel", null).show();
    }

    /** Checks both runtime permissions without assuming they remain granted. */
    private boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Registers notifications only when read permission is actually available. */
    private void registerObserver() {
        if (observerRegistered || !hasPermissions()) {
            return;
        }
        try {
            getContentResolver().registerContentObserver(
                    ContactsContract.RawContacts.CONTENT_URI, true, observer);
            getContentResolver().registerContentObserver(
                    ContactsContract.Data.CONTENT_URI, true, observer);
            observerRegistered = true;
        } catch (SecurityException error) {
            getContentResolver().unregisterContentObserver(observer);
            showError("Could not monitor contacts", error);
        }
    }

    /** Lists provider-visible account-scoped books for selecting a distinct pair. */
    private void showSettings() {
        try {
            List<AddressBook> books = ContactBooks.discover(this);
            resetView("Settings");
            text("Books are discovered from Android contacts and available contacts accounts. "
                    + "Destination writability depends on its account provider.");
            if (books.size() < 2) {
                text("At least two distinct visible address books are needed. "
                        + "Check contact permissions and account availability.");
                button("Back", this::showMain);
                return;
            }
            Spinner source = spinner("From", books);
            Spinner destination = spinner("To", books);
            SyncSettings settings = new SyncSettings(this);
            int sourceIndex = books.indexOf(settings.source());
            int destinationIndex = books.indexOf(settings.destination());
            source.setSelection(sourceIndex < 0 ? 0 : sourceIndex);
            destination.setSelection(destinationIndex < 0 ? 1 : destinationIndex);
            button("Save selection", () -> confirmSelection(
                    (AddressBook) source.getSelectedItem(), (AddressBook) destination.getSelectedItem()));
            button("Cancel", this::showMain);
        } catch (JSONException | IllegalStateException | SecurityException error) {
            showError("Could not list address books", error);
        }
    }

    /** Confirms direction and the deletion risk before saving a pair. */
    private void confirmSelection(AddressBook source, AddressBook destination) {
        if (source.equals(destination)) {
            message("Choose different From and To address books.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Confirm address books")
                .setMessage("From: " + source.label() + "\nTo: " + destination.label()
                        + "\n\nSync writes source details to the destination. Deleting a source "
                        + "contact deletes its linked destination contact, including any "
                        + "destination-only information. Existing matched destination contacts "
                        + "can also be deleted.")
                .setPositiveButton("Confirm", (dialog, which) -> saveSelection(source, destination))
                .setNegativeButton("Cancel", null).show();
    }

    /** Commits configuration under the same lock used by a monitoring scan. */
    private void saveSelection(AddressBook source, AddressBook destination) {
        try {
            synchronized (SyncSettings.LOCK) {
                ActivityLog log = new ActivityLog(this);
                log.append("Selected sync pair From " + source.label() + " to " + destination.label());
                new SyncSettings(this).select(source, destination);
                MonitorScheduler.stop(this);
                MonitorScheduler.start(this);
            }
            showMain();
        } catch (IOException | JSONException | IllegalArgumentException
                 | IllegalStateException | SecurityException error) {
            showError("Could not save selection", error);
        }
    }

    /** Shows each ambiguous phone match and conflicting field as a separate decision. */
    private void showDecisions() {
        try {
            SyncSettings settings = new SyncSettings(this);
            AddressBook source = settings.source();
            AddressBook destination = settings.destination();
            if (source == null || destination == null) {
                showMain();
                return;
            }
            String pair = SyncStateStore.pairKey(source, destination);
            try (SyncStateStore state = new SyncStateStore(this)) {
                resetView("Review decisions");
                for (ReconciliationPlanner.MatchDecision match : state.matches(pair)) {
                    text("Source raw contact " + match.sourceId() + " matches multiple "
                            + "destination contacts. Choose one, or deliberately copy as new.");
                    for (String candidate : match.candidates()) {
                        button("Link to destination " + candidate, () ->
                                resolveMatch(match, candidate, false));
                    }
                    button("Copy as new", () -> resolveMatch(match, null, true));
                }
                for (SyncConflict conflict : state.conflicts(pair)) {
                    text("Conflicting field: " + friendlyField(conflict.field())
                            + "\nFrom: " + conflict.sourceValues()
                            + "\nTo: " + conflict.destinationValues());
                    button("Use From values", () -> resolveConflict(conflict, true));
                    button("Keep To values", () -> resolveConflict(conflict, false));
                }
                if (state.matches(pair).isEmpty() && state.conflicts(pair).isEmpty()) {
                    text("No pending decisions.");
                }
            }
            button("Back", this::showMain);
        } catch (Exception error) {
            showError("Could not load pending decisions", error);
        }
    }

    /** Applies a current user resolution and requests fresh reconciliation. */
    private void resolveConflict(SyncConflict conflict, boolean useSource) {
        try {
            SyncEngine.resolveConflict(this, conflict, useSource);
            showDecisions();
        } catch (Exception error) {
            showError("Could not resolve contact conflict", error);
        }
    }

    /** Validates and applies a chosen ambiguous phone match. */
    private void resolveMatch(ReconciliationPlanner.MatchDecision match, String candidate,
            boolean copyAsNew) {
        try {
            SyncEngine.resolveMatch(this, match.sourceId(), candidate, copyAsNew,
                    match.sourceVersion());
            showDecisions();
        } catch (Exception error) {
            showError("Could not resolve phone match", error);
        }
    }

    /** Converts common provider MIME names to labels readable in the review screen. */
    private String friendlyField(String mime) {
        String[] parts = mime.split("\\.");
        return parts.length == 0 ? mime : parts[parts.length - 1];
    }

    /** Pauses or resumes persistent observation, requesting a check on resume. */
    private void toggleMonitoring() {
        try {
            synchronized (SyncSettings.LOCK) {
                SyncSettings settings = new SyncSettings(this);
                boolean enabled = !settings.enabled();
                new ActivityLog(this).append(enabled ? "Monitoring resumed" : "Monitoring paused");
                settings.setEnabled(enabled);
                if (enabled) {
                    MonitorScheduler.start(this);
                } else {
                    MonitorScheduler.stop(this);
                }
            }
            showMain();
        } catch (IOException | IllegalStateException error) {
            showError("Could not change monitoring state", error);
        }
    }

    /** Shows the private log and explicit export and clearing actions. */
    private void showLog() {
        try {
            resetView("Log");
            text("The log contains sensitive contact information. Apps you share it with "
                    + "may retain or transmit a copy.");
            button("Open log", () -> confirmExport(false));
            button("Share log", () -> confirmExport(true));
            button("Clear log", this::confirmClear);
            button("Back", this::showMain);
            text(new ActivityLog(this).read());
        } catch (IOException error) {
            showError("Could not read log", error);
        }
    }

    /** Requires explicit acknowledgement before exposing contact information. */
    private void confirmExport(boolean share) {
        new AlertDialog.Builder(this).setTitle("Export sensitive log")
                .setMessage("The selected app can read and retain contact information in this log.")
                .setPositiveButton("Continue", (dialog, which) -> exportLog(share))
                .setNegativeButton("Cancel", null).show();
    }

    /** Grants the selected external application temporary read-only URI access. */
    private void exportLog(boolean share) {
        try {
            File export = new ActivityLog(this).export();
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", export);
            Intent intent = new Intent(share ? Intent.ACTION_SEND : Intent.ACTION_VIEW);
            intent.setType("text/plain");
            if (share) {
                intent.putExtra(Intent.EXTRA_STREAM, uri);
            } else {
                intent.setDataAndType(uri, "text/plain");
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, share ? "Share log" : "Open log"));
        } catch (ActivityNotFoundException error) {
            showError("No compatible app is available", error);
        } catch (IOException | IllegalArgumentException | SecurityException error) {
            showError("Could not export log", error);
        }
    }

    /** Asks before irreversibly clearing the local log. */
    private void confirmClear() {
        new AlertDialog.Builder(this).setTitle("Clear log?")
                .setMessage("Prior entries will be removed. Exported copies are not affected.")
                .setPositiveButton("Clear", (dialog, which) -> {
                    try {
                        new ActivityLog(this).clear();
                        showLog();
                    } catch (IOException error) {
                        showError("Could not clear log", error);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** Creates a simple scrollable screen with a title. */
    private void resetView(String title) {
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(32, 32, 32, 32);
        scroll.addView(content);
        setContentView(scroll);
        setTitle(title);
        text(title);
    }

    /** Adds readable text to the current screen. */
    private void text(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextIsSelectable(true);
        view.setPadding(0, 12, 0, 12);
        content.addView(view);
    }

    /** Adds an action button to the current screen. */
    private void button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        content.addView(button);
    }

    /** Adds a labeled address-book selector. */
    private Spinner spinner(String label, List<AddressBook> books) {
        text(label);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<AddressBook> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, books);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        content.addView(spinner);
        return spinner;
    }

    /** Shows a user-visible error instead of silently ignoring failures. */
    private void showError(String title, Exception error) {
        new AlertDialog.Builder(this).setTitle(title)
                .setMessage(error.getClass().getSimpleName() + ": " + error.getMessage())
                .setPositiveButton("OK", null).show();
    }

    /** Shows informational feedback for queued or invalid actions. */
    private void message(String message) {
        new AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show();
    }

    /** Opens the Android screen where permissions can be changed. */
    private void openSystemSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            showError("Could not open Android settings", error);
        }
    }
}
