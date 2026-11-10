package org.synccontacts.app;

import android.content.Context;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Schedules persistent, unique source-monitoring work. */
public final class MonitorScheduler {
    public static final String PERIODIC = "source-monitor-periodic";
    public static final String IMMEDIATE = "source-monitor-immediate";

    private MonitorScheduler() {
    }

    /** Keeps one persistent four-hour schedule without running unless explicitly requested. */
    public static void start(Context context) {
        WorkManager manager = WorkManager.getInstance(context);
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                MonitorWorker.class, 4, TimeUnit.HOURS).build();
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic);
    }

    /** Coalesces redundant provider notifications into one immediate check. */
    public static void request(Context context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE, ExistingWorkPolicy.KEEP,
                new OneTimeWorkRequest.Builder(MonitorWorker.class).build());
    }

    /** Stops both periodic and queued immediate work. */
    public static void stop(Context context) {
        WorkManager manager = WorkManager.getInstance(context);
        manager.cancelUniqueWork(PERIODIC);
        manager.cancelUniqueWork(IMMEDIATE);
    }
}
