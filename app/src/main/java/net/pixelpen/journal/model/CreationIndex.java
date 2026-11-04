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

    /**
     * Forget a note, after it is deleted.
     *
     * <p>Without this a deleted entry stays in the map under its path, and a new note later given
     * the same title in the same folder would inherit the dead one's creation time and sort to
     * where the old entry used to be.
     */
    public synchronized void remove(final File f) {
        if (f != null) {
            props.remove(f.getAbsolutePath());
        }
    }

    /**
     * Carry a note's creation time to its new path, after a rename or a move.
     *
     * <p>This map is keyed by absolute path, so moving a file otherwise orphans its entry: the
     * note arrives at the new path with nothing recorded and falls back to its modified time,
     * which the move itself may just have changed. Renaming an entry out of its folder would then
     * quietly reorder it.
     */
    public synchronized void moved(final File from, final File to) {
        if (from == null || to == null) {
            return;
        }
        final String v = props.getProperty(from.getAbsolutePath());
        if (v != null) {
            props.remove(from.getAbsolutePath());
            props.setProperty(to.getAbsolutePath(), v);
        }
    }

    /** Persist to disk; call after batching writes. */
    public synchronized void save() {
        try (FileOutputStream out = new FileOutputStream(storeFile)) {
            props.store(out, "Autumn creation index: absolute path -> creation time (epoch millis)");
        } catch (Exception ignored) { }
    }
}
