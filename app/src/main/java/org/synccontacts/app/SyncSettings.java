package org.synccontacts.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;

/** Persists the address-book pair, background-sync preference, and log setting. */
public final class SyncSettings {
    public static final Object LOCK = new Object();
    private final SharedPreferences preferences;

    /** Opens settings stored privately to the app. */
    public SyncSettings(Context context) {
        preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** Returns the selected source or null when setup is incomplete. */
    public AddressBook source() throws JSONException {
        String value = preferences.getString("source", null);
        return value == null ? null : AddressBook.decode(value);
    }

    /** Returns the selected destination or null when setup is incomplete. */
    public AddressBook destination() throws JSONException {
        String value = preferences.getString("destination", null);
        return value == null ? null : AddressBook.decode(value);
    }

    /** Indicates whether automatic background synchronization is enabled. */
    public boolean backgroundSyncEnabled() {
        return preferences.getBoolean("background_sync_enabled", true);
    }

    /** Returns the persisted interval or the default for existing and new installations. */
    public SyncInterval syncInterval() {
        return SyncInterval.fromMinutes(preferences.getInt("sync_interval_minutes", 15));
    }

    /** Stores the chosen background interval for the next schedule update. */
    public void setSyncInterval(SyncInterval interval) {
        if (!preferences.edit().putInt("sync_interval_minutes", interval.minutes()).commit()) {
            throw new IllegalStateException("Could not save background sync interval");
        }
    }

    /** Indicates whether optional activity-log entries are recorded. */
    public boolean loggingEnabled() {
        return preferences.getBoolean("logging_enabled", false);
    }

    /** Changes logging atomically relative to activity-log appends. */
    public void setLoggingEnabled(boolean enabled) {
        synchronized (LOCK) {
            if (!preferences.edit().putBoolean("logging_enabled", enabled).commit()) {
                throw new IllegalStateException("Could not save logging setting");
            }
        }
    }

    /** Identifies settings changes so workers can reject stale scans. */
    public long generation() {
        return preferences.getLong("generation", 0);
    }

    /** Stores a new pair atomically and invalidates scans from the old pair. */
    public void select(AddressBook source, AddressBook destination) throws JSONException {
        if (source.equals(destination)) {
            throw new IllegalArgumentException("Source and destination must differ");
        }
        boolean saved = preferences.edit().putString("source", source.encode())
                .putString("destination", destination.encode())
                .putString("status", "Source check requested; no check has completed for this selection.")
                .putLong("generation", generation() + 1).commit();
        if (!saved) {
            throw new IllegalStateException("Could not save settings");
        }
    }

    /** Stores the background-sync preference without changing the selected pair. */
    public void setBackgroundSyncEnabled(boolean enabled) {
        if (!preferences.edit().putBoolean("background_sync_enabled", enabled).commit()) {
            throw new IllegalStateException("Could not save background-sync setting");
        }
    }

    /** Stores an honest status message for display on the main screen. */
    public void status(String message) {
        if (!preferences.edit().putString("status", message).commit()) {
            throw new IllegalStateException("Could not save background sync status");
        }
    }

    /** Returns the latest persisted synchronization status. */
    public String status() {
        return preferences.getString("status", "No check has completed.");
    }
}
