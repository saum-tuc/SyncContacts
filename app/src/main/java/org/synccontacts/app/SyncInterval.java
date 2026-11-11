package org.synccontacts.app;

/** Lists supported periodic background-sync intervals. */
public enum SyncInterval {
    MINUTES_15(15, "15 minutes"),
    MINUTES_30(30, "30 minutes"),
    HOUR_1(60, "1 hour"),
    HOURS_2(120, "2 hours"),
    HOURS_4(240, "4 hours"),
    HOURS_12(720, "12 hours"),
    HOURS_24(1440, "24 hours");

    private final int minutes;
    private final String label;

    /** Associates a stored duration with its settings label. */
    SyncInterval(int minutes, String label) {
        this.minutes = minutes;
        this.label = label;
    }

    /** Returns the interval accepted by WorkManager. */
    public int minutes() {
        return minutes;
    }

    /** Rejects invalid stored values rather than silently changing the schedule. */
    public static SyncInterval fromMinutes(int minutes) {
        for (SyncInterval interval : values()) {
            if (interval.minutes == minutes) {
                return interval;
            }
        }
        throw new IllegalStateException("Unsupported background sync interval: " + minutes);
    }

    /** Supplies the human-readable label for the settings selector. */
    @Override
    public String toString() {
        return label;
    }
}
