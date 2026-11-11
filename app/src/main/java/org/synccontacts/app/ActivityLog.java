package org.synccontacts.app;

import android.content.Context;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Stores a private UTF-8 log with durable, atomic replacement. */
public final class ActivityLog {
    private static final Object LOCK = new Object();
    private final AtomicFile file;
    private final File exportDirectory;
    private final SyncSettings settings;

    /** Uses app-private storage, never an externally writable log. */
    public ActivityLog(Context context) {
        file = new AtomicFile(new File(context.getFilesDir(), "activity.log"));
        exportDirectory = new File(context.getCacheDir(), "exports");
        settings = new SyncSettings(context);
    }

    /** Reads the entire log or returns an empty log when none exists yet. */
    public String read() throws IOException {
        synchronized (LOCK) {
            byte[] contents = AtomicFiles.readIfPresent(file);
            if (contents == null) {
                return "";
            }
            return new String(contents, StandardCharsets.UTF_8);
        }
    }

    /** Appends an event atomically; failures propagate to the caller. */
    public void append(String message) throws IOException {
        appendAll(List.of(message));
    }

    /** Commits events only while logging is enabled, without touching prior entries otherwise. */
    public void appendAll(List<String> messages) throws IOException {
        synchronized (SyncSettings.LOCK) {
            if (!settings.loggingEnabled()) {
                return;
            }
            synchronized (LOCK) {
                StringBuilder updated = new StringBuilder(read());
                for (String message : messages) {
                    updated.append(Instant.now()).append(' ').append(message).append('\n');
                }
                write(updated.toString());
            }
        }
    }

    /** Clears prior entries and records the clear event only when logging is enabled. */
    public void clear() throws IOException {
        synchronized (SyncSettings.LOCK) {
            synchronized (LOCK) {
                removeExports();
                write(settings.loggingEnabled() ? Instant.now() + " Log cleared by user\n" : "");
            }
        }
    }

    /** Produces a separate read-only-shareable snapshot in the exports directory. */
    public File export() throws IOException {
        synchronized (LOCK) {
            if (!exportDirectory.exists() && !exportDirectory.mkdirs()) {
                throw new IOException("Unable to create export directory");
            }
            // A previous recipient must not gain access to a later export through an old URI.
            File exported = new File(exportDirectory,
                    "synccontacts-log-" + UUID.randomUUID() + ".txt");
            try (FileOutputStream stream = new FileOutputStream(exported)) {
                stream.write(read().getBytes(StandardCharsets.UTF_8));
                stream.getFD().sync();
            }
            return exported;
        }
    }

    /** Removes cached exports so clearing the log does not retain private copies. */
    private void removeExports() throws IOException {
        if (!exportDirectory.exists()) {
            return;
        }
        File[] exports = exportDirectory.listFiles();
        if (exports == null) {
            throw new IOException("Unable to inspect exported logs");
        }
        for (File exported : exports) {
            String name = exported.getName();
            if ((name.startsWith("synccontacts-log-") || name.equals("synccontacts-log.txt"))
                    && name.endsWith(".txt") && !exported.delete()) {
                throw new IOException("Unable to delete cached log export");
            }
        }
    }

    /** Commits the replacement fully or keeps the previous complete file. */
    private void write(String text) throws IOException {
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(text.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (IOException | RuntimeException error) {
            file.failWrite(stream);
            throw error;
        }
    }
}
