package net.pixelpen.journal.model;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/** Tiny on-disk map: absolutePath -> createdMillis (epoch). */
public class CreationIndex {
    private static final String FILENAME = "creation_index.properties";
    private final File storeFile;
    private final Properties props = new Properties();

    public CreationIndex(Context ctx) {
        storeFile = new File(ctx.getFilesDir(), FILENAME);
        load();
    }

    private synchronized void load() {
        if (!storeFile.exists()) return;
        try (FileInputStream in = new FileInputStream(storeFile)) {
            props.load(in);
        } catch (Exception ignored) { }
    }

    public synchronized void put(File f, long createdMillis) {
        if (f == null) return;
        props.setProperty(f.getAbsolutePath(), Long.toString(createdMillis));
    }

    public synchronized Long get(File f) {
        if (f == null) return null;
        String v = props.getProperty(f.getAbsolutePath());
        if (v == null) return null;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Persist to disk; call after batching writes. */
    public synchronized void save() {
        try (FileOutputStream out = new FileOutputStream(storeFile)) {
            props.store(out, "Markor Creation Index");
        } catch (Exception ignored) { }
    }
}
