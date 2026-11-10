package org.synccontacts.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;

/** Persists the active address-book pair and monitoring state. */
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

    /** Indicates whether monitoring is enabled for a completed configuration. */
    public boolean enabled() {
        return preferences.getBoolean("enabled", false);
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
                .putString("destination", destination.encode()).putBoolean("enabled", true)
                .putString("status", "Source check requested; no check has completed for this selection.")
                .putLong("generation", generation() + 1).commit();
        if (!saved) {
            throw new IllegalStateException("Could not save settings");
        }
    }

    /** Changes monitoring state and invalidates any in-progress scan. */
    public void setEnabled(boolean enabled) {
        if (!preferences.edit().putBoolean("enabled", enabled)
                .putLong("generation", generation() + 1).commit()) {
            throw new IllegalStateException("Could not save monitoring state");
        }
    }

    /** Stores an honest status message for display on the main screen. */
    public void status(String message) {
        if (!preferences.edit().putString("status", message).commit()) {
            throw new IllegalStateException("Could not save monitoring status");
        }
    }

    /** Returns the latest persisted monitoring status. */
    public String status() {
        return preferences.getString("status", "No check has completed.");
    }
}
