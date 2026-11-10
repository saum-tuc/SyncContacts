package org.synccontacts.app;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Runs synchronized reconciliation and crash-aware destination operations. */
public final class MonitorWorker extends Worker {
    /** Creates a persistent WorkManager observation task. */
    public MonitorWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    /** Reads a complete snapshot and logs changes if the configuration remains active. */
    @NonNull
    @Override
    public Result doWork() {
        return SyncEngine.run(getApplicationContext(), this::isStopped);
    }
}
