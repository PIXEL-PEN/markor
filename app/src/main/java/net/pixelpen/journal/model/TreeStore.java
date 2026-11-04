package net.pixelpen.journal.model;

import net.pixelpen.journal.util.NoteTimestamp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One directory's worth of the archive, scanned on demand and cached.
 *
 * <p>This replaces FolderTreeScanner, which recursed the whole archive: it walked every year,
 * every month and every category from the root on every call, read the first lines of every
 * long-titled note to decide a badge, and did it again each time the Tree tab became visible.
 * That cost grows with the archive, which is the wrong direction for something meant to
 * accumulate for years.
 *
 * <p>Here a directory is read only when it is actually about to be drawn, and the result is kept
 * until something invalidates it. Opening a month reads that month.
 *
 * <p>The yearly summary will want a whole-archive walk by design, but it wants it on a schedule
 * and off the main thread, not on every glance at a tab, so it should ask for one explicitly
 * rather than inherit one.
 */
public class TreeStore {

    /** Folder names like {@code 01_January} display as {@code January}. */
    private static final Pattern NUM_PREFIX = Pattern.compile("^\\d{1,2}[_-]");

    private static final Pattern STRIP_SUFFIX =
            Pattern.compile("\\.(md|markdown|txt)$", Pattern.CASE_INSENSITIVE);

    private static final Set<String> DOC_EXTS = new HashSet<>();

    static {
        DOC_EXTS.add("md");
        DOC_EXTS.add("markdown");
        DOC_EXTS.add("txt");
        DOC_EXTS.add("html");
        DOC_EXTS.add("htm");
    }

    /**
     * Title length at which a note is treated as "the title is the entry" and gets a badge.
     * Carried over from the WebView unchanged.
     */
    private static final int TITLE_LEN_THRESHOLD = 120;

    /** A note at least this big counts as written-into without reading it. */
    private static final long SIZE_THRESHOLD_BYTES = 2L * 1024L;

    /** One row of the tree: a subdirectory or a document. */
    public static final class Entry {
        public final File file;
        public final boolean isDir;
        /** What the row shows: {@code NN_} stripped from folders, extension stripped from notes. */
        public final String display;
        /** Resolved creation time, used for ordering and for the date line. */
        public final long createdMillis;
        /** Title long enough that the title itself is the entry. */
        public final boolean longTitle;
        /** Has content past the date stamp on line 1. Only meaningful when {@link #longTitle}. */
        public final boolean substantial;

        /**
         * A directory row the store did not produce itself -- the archive root, which has no
         * parent listing to come from.
         */
        public static Entry forDirectory(final File dir) {
            return new Entry(dir, true, NUM_PREFIX.matcher(dir.getName()).replaceFirst(""),
                    0L, false, false);
        }

        Entry(final File file, final boolean isDir, final String display,
              final long createdMillis, final boolean longTitle, final boolean substantial) {
            this.file = file;
            this.isDir = isDir;
            this.display = display;
            this.createdMillis = createdMillis;
            this.longTitle = longTitle;
            this.substantial = substantial;
        }
    }

    /** Subdirectories and documents of one directory, each already in display order. */
    public static final class Dir {
        public final List<Entry> dirs;
        public final List<Entry> files;

        Dir(final List<Entry> dirs, final List<Entry> files) {
            this.dirs = dirs;
            this.files = files;
        }

        public boolean isEmpty() {
            return dirs.isEmpty() && files.isEmpty();
        }
    }

    private static final Dir EMPTY =
            new Dir(Collections.<Entry>emptyList(), Collections.<Entry>emptyList());

    private final Map<String, Dir> _cache = new HashMap<>();
    private final CreationIndex _index;

    public TreeStore(final CreationIndex index) {
        _index = index;
    }

    /** Already-scanned contents, or null if this directory has not been read yet. */
    public synchronized Dir peek(final File dir) {
        return (dir == null) ? EMPTY : _cache.get(dir.getAbsolutePath());
    }

    /**
     * Read a directory and cache it. Call off the main thread: this touches the filesystem and,
     * for long-titled notes, reads the start of each file.
     */
    public Dir load(final File dir) {
        if (dir == null || !dir.isDirectory()) {
            return EMPTY;
        }
        final Dir cached = peek(dir);
        if (cached != null) {
            return cached;
        }
        final Dir scanned = scan(dir);
        synchronized (this) {
            _cache.put(dir.getAbsolutePath(), scanned);
        }
        return scanned;
    }

    /** Forget everything, so the next draw re-reads from disk. */
    public synchronized void invalidateAll() {
        _cache.clear();
    }

    /** Forget one directory, after a note is added to or removed from it. */
    public synchronized void invalidate(final File dir) {
        if (dir != null) {
            _cache.remove(dir.getAbsolutePath());
        }
    }

    private Dir scan(final File dir) {
        final File[] entries = dir.listFiles();
        if (entries == null) {
            // Unreadable rather than empty -- a permissions problem looks the same as an empty
            // month here, which is worth keeping in mind if a folder ever shows up bare.
            return EMPTY;
        }

        final List<Entry> dirs = new ArrayList<>();
        final List<Entry> files = new ArrayList<>();

        for (final File f : entries) {
            final String name = f.getName();
            if (name.startsWith(".") || "_res".equals(name)) {
                continue;
            }
            if (f.isDirectory()) {
                if (new File(f, ".nomedia").exists() || new File(f, ".skip-scan").exists()) {
                    continue;
                }
                dirs.add(new Entry(f, true, NUM_PREFIX.matcher(name).replaceFirst(""), 0L, false, false));
            } else if (f.isFile() && isDoc(name)) {
                files.add(fileEntry(f, name));
            }
        }

        // Folders: by leading number where there is one (so months read January..December rather
        // than April, August, ...), otherwise by name.
        Collections.sort(dirs, (a, b) -> {
            final int na = leadingNumber(a.file.getName());
            final int nb = leadingNumber(b.file.getName());
            if (na != nb) {
                return Integer.compare(na, nb);
            }
            return a.file.getName().compareToIgnoreCase(b.file.getName());
        });

        // Notes: newest first by creation time, which is the order this app exists to preserve.
        //
        // The old scanner gave up on this above 500 files in one directory and fell back to
        // alphabetical, which silently destroyed the one invariant that matters. It did that
        // because resolving each file's time could hit the filesystem; sorting an in-memory list
        // of a few thousand longs does not need a threshold, so there isn't one here.
        //
        // Name is the tiebreak, so two notes captured in the same minute keep a stable order
        // instead of shuffling between draws.
        Collections.sort(files, (a, b) -> {
            final int byTime = Long.compare(b.createdMillis, a.createdMillis);
            if (byTime != 0) {
                return byTime;
            }
            return a.file.getName().compareToIgnoreCase(b.file.getName());
        });

        if (_index != null) {
            _index.save();
        }
        return new Dir(dirs, files);
    }

    private Entry fileEntry(final File f, final String name) {
        final String display = STRIP_SUFFIX.matcher(name).replaceAll("");
        final boolean longTitle = display.length() >= TITLE_LEN_THRESHOLD;
        // Only long-titled notes show a badge, so only they are worth opening to look inside.
        final boolean substantial = longTitle
                && (f.length() >= SIZE_THRESHOLD_BYTES || hasContentBeyondStamp(f));
        return new Entry(f, false, display, resolveCreated(f), longTitle, substantial);
    }

    /**
     * When this note was created.
     *
     * <p>This is the single seam for creation time in the tree, and the place the rest of the
     * creation-order work lands. Today it is the stored index, falling back to the file's modified
     * time and remembering that answer -- which is what the WebView sorted by.
     *
     * <p>What it does not yet do is prefer the date stamp written as line 1 of every note. That
     * stamp is the durable record: it survives copying, syncing and restoring, all of which reset
     * a filesystem timestamp. {@link NoteTimestamp#parseStamp} reads it. Wiring it in means
     * deciding what to do with index entries already backfilled from modified times, which needs
     * provenance stored alongside each value and a one-time re-derive -- a change to the index
     * format, not to the tree, so it is deliberately not bundled in here.
     */
    private long resolveCreated(final File f) {
        if (_index != null) {
            final Long stored = _index.get(f);
            if (stored != null && stored > 0L) {
                return stored;
            }
        }
        final long fallback = f.lastModified();
        if (_index != null) {
            _index.put(f, fallback);
        }
        return fallback;
    }

    /** True if anything follows the date stamp on line 1. */
    private static boolean hasContentBeyondStamp(final File f) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    return true;
                }
            }
            return false;
        } catch (final Exception e) {
            return false;
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (final Exception ignore) {
                // Nothing useful to do if a read-only handle will not close.
            }
        }
    }

    private static boolean isDoc(final String name) {
        final String n = name.toLowerCase(Locale.ROOT);
        final int dot = n.lastIndexOf('.');
        return dot >= 0 && DOC_EXTS.contains(n.substring(dot + 1));
    }

    private static int leadingNumber(final String name) {
        int i = 0;
        while (i < name.length() && Character.isDigit(name.charAt(i))) {
            i++;
        }
        if (i == 0 || i > 2 || i >= name.length()) {
            return Integer.MAX_VALUE;
        }
        final char sep = name.charAt(i);
        if (sep != '_' && sep != '-') {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(name.substring(0, i));
        } catch (final NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
