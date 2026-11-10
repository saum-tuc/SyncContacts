package org.synccontacts.app;

import android.util.AtomicFile;

import java.io.File;
import java.io.IOException;

/** Reads optional atomic files without overlooking interrupted or backup files. */
public final class AtomicFiles {
    private AtomicFiles() {
    }

    /** Returns null only when no base, backup, or incomplete write exists. */
    public static byte[] readIfPresent(AtomicFile file) throws IOException {
        File base = file.getBaseFile();
        File backup = new File(base.getPath() + ".bak");
        File pending = new File(base.getPath() + ".new");
        if (!base.exists() && !backup.exists()) {
            if (pending.exists()) {
                throw new IOException("Incomplete atomic write for " + base.getName());
            }
            return null;
        }
        return file.readFully();
    }
}
