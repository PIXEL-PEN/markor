package net.pixelpen.journal.model;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Search the whole archive for a word.
 *
 * <p>Deliberately the opposite of {@link TreeStore}, which reads one directory at a time and only
 * when it is about to be drawn. Search cannot be lazy -- a note matches or not wherever it sits --
 * so this walks everything, and it is only ever run because someone typed a query.
 *
 * <p>Titles are matched first and cheaply. A note's body is only opened when its title did not
 * match, which for this archive skips most of the reading: a long title here IS the entry, so the
 * thing being searched for is usually in the name.
 */
public final class ArchiveSearch {

    /** Stop after this many matches. A query of "e" should not try to list the whole archive. */
    private static final int MAX_HITS = 500;

    /** Do not read the body of anything bigger than this looking for a match. */
    private static final long MAX_BODY_BYTES = 1024L * 1024L;

    /** Guard against a symlink loop or a pathologically deep tree. */
    private static final int MAX_DEPTH = 12;

    private static final Set<String> DOC_EXTS = new HashSet<>();

    static {
        DOC_EXTS.add("md");
        DOC_EXTS.add("markdown");
        DOC_EXTS.add("txt");
        DOC_EXTS.add("html");
        DOC_EXTS.add("htm");
    }

    private ArchiveSearch() {
    }

    /** One matching note. */
    public static final class Hit {
        public final File file;
        /** Title without its extension. */
        public final String display;
        /** Folder holding it, relative to the archive root, for the line under the title. */
        public final String where;
        public final long createdMillis;
        /** True when the title did not match and the body did. */
        public final boolean inBody;
        /**
         * Zero-based line the match was found on, or -1 for a title match. Passed to the editor
         * so opening a result lands on the line rather than at the top of the note.
         */
        public final int line;

        Hit(final File file, final String display, final String where,
            final long createdMillis, final boolean inBody, final int line) {
            this.file = file;
            this.display = display;
            this.where = where;
            this.createdMillis = createdMillis;
            this.inBody = inBody;
            this.line = line;
        }
    }

    /**
     * Notes matching {@code query}, newest first.
     *
     * <p>Call off the main thread. Returns an empty list for a blank query rather than everything,
     * because an empty field means "not searching" and should show the tree, not the archive
     * flattened.
     */
    public static List<Hit> run(final File root, final String query, final CreationIndex index) {
        final List<Hit> hits = new ArrayList<>();
        if (root == null || !root.isDirectory() || query == null) {
            return hits;
        }
        final String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return hits;
        }

        final String rootPath = root.getAbsolutePath();
        // Iterative rather than recursive: the depth cap already bounds this, and a queue keeps
        // the walk flat so a deep archive cannot put a limit on the stack instead.
        final Deque<File> queue = new ArrayDeque<>();
        final Deque<Integer> depths = new ArrayDeque<>();
        queue.add(root);
        depths.add(0);

        while (!queue.isEmpty() && hits.size() < MAX_HITS) {
            final File dir = queue.poll();
            final int depth = depths.poll();
            final File[] entries = dir.listFiles();
            if (entries == null) {
                continue;
            }
            for (final File f : entries) {
                if (hits.size() >= MAX_HITS) {
                    break;
                }
                final String name = f.getName();
                if (name.startsWith(".") || "_res".equals(name)) {
                    continue;
                }
                if (f.isDirectory()) {
                    if (depth < MAX_DEPTH
                            && !new File(f, ".nomedia").exists()
                            && !new File(f, ".skip-scan").exists()) {
                        queue.add(f);
                        depths.add(depth + 1);
                    }
                    continue;
                }
                if (!f.isFile() || !isDoc(name)) {
                    continue;
                }

                final String display = stripExtension(name);
                final boolean titleMatch = display.toLowerCase(Locale.ROOT).contains(needle);
                // Only open the file when the name did not already answer the question.
                final int bodyLine = titleMatch ? -1 : findInBody(f, needle);
                if (!titleMatch && bodyLine < 0) {
                    continue;
                }
                hits.add(new Hit(f, display, relative(rootPath, f.getParentFile()),
                        created(f, index), bodyLine >= 0, bodyLine));
            }
        }

        // Newest first, matching the tree, with name as the tiebreak so equal timestamps do not
        // shuffle between searches.
        Collections.sort(hits, (a, b) -> {
            final int byTime = Long.compare(b.createdMillis, a.createdMillis);
            return (byTime != 0) ? byTime : a.display.compareToIgnoreCase(b.display);
        });
        return hits;
    }

    /** True if the search hit the result cap and so may be showing only part of the matches. */
    public static boolean wasCapped(final List<Hit> hits) {
        return hits != null && hits.size() >= MAX_HITS;
    }

    private static long created(final File f, final CreationIndex index) {
        if (index != null) {
            final Long stored = index.get(f);
            if (stored != null && stored > 0L) {
                return stored;
            }
        }
        return f.lastModified();
    }

    /**
     * Zero-based line of the first match in the body, or -1 if there is none.
     *
     * <p>Returns the line rather than just a yes, so opening a result can land on the match.
     * Finding the note and then dumping the reader at the top of it is only half an answer when
     * the note is long.
     */
    private static int findInBody(final File f, final String needle) {
        if (f.length() > MAX_BODY_BYTES) {
            return -1;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
            String line;
            int n = 0;
            while ((line = br.readLine()) != null) {
                if (line.toLowerCase(Locale.ROOT).contains(needle)) {
                    return n;
                }
                n++;
            }
            return -1;
        } catch (final Exception e) {
            // An unreadable note is not a match. Failing the whole search over one file would be
            // worse than missing it.
            return -1;
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

    private static String relative(final String rootPath, final File dir) {
        if (dir == null) {
            return "";
        }
        final String p = dir.getAbsolutePath();
        if (p.equals(rootPath)) {
            return "";
        }
        if (p.startsWith(rootPath)) {
            return p.substring(rootPath.length()).replaceFirst("^/", "");
        }
        return p;
    }

    private static String stripExtension(final String name) {
        final int dot = name.lastIndexOf('.');
        final String ext = (dot > 0) ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        // .html stays visible in the tree, so it stays visible here too.
        return (dot > 0 && ("md".equals(ext) || "markdown".equals(ext) || "txt".equals(ext)))
                ? name.substring(0, dot)
                : name;
    }

    private static boolean isDoc(final String name) {
        final String n = name.toLowerCase(Locale.ROOT);
        final int dot = n.lastIndexOf('.');
        return dot >= 0 && DOC_EXTS.contains(n.substring(dot + 1));
    }
}
