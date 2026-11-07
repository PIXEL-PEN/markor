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
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
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

    /**
     * A parsed query.
     *
     * <p>Plain substring matching was the whole engine, so "blue fridge" found nothing unless
     * those nine characters sat together. Markor could do better and this could not. Three
     * forms now, in rising order of how often they are wanted:
     *
     * <pre>
     *   blue fridge     every term must appear, in the title or anywhere in the body
     *   "blue fridge"   that phrase, as written
     *   /fridge?s/      a regular expression, case-insensitive
     * </pre>
     *
     * <p>Terms AND rather than OR because a search that widens as you type more is not a
     * search. Quotes nest inside the term list, so {@code milk "blue fridge"} is two
     * conditions.
     */
    static final class Query {
        final List<String> terms;
        final Pattern regex;

        private Query(final List<String> terms, final Pattern regex) {
            this.terms = terms;
            this.regex = regex;
        }

        boolean isEmpty() {
            return regex == null && terms.isEmpty();
        }

        /** How many conditions this lowercased text satisfies. */
        int score(final String lower) {
            if (regex != null) {
                return regex.matcher(lower).find() ? 1 : 0;
            }
            int n = 0;
            for (final String t : terms) {
                if (lower.contains(t)) {
                    n++;
                }
            }
            return n;
        }

        int conditions() {
            return (regex != null) ? 1 : terms.size();
        }
    }

    static Query parse(final String raw) {
        final String q = (raw == null) ? "" : raw.trim();
        if (q.length() > 2 && q.startsWith("/") && q.endsWith("/")) {
            try {
                return new Query(Collections.<String>emptyList(),
                        Pattern.compile(q.substring(1, q.length() - 1),
                                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            } catch (final PatternSyntaxException e) {
                // Half-typed regex. Fall through and treat it as text rather than showing
                // nothing while the user is still typing the pattern.
            }
        }
        final List<String> terms = new ArrayList<>();
        final StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < q.length(); i++) {
            final char c = q.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    terms.add(cur.toString().toLowerCase(Locale.ROOT));
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            terms.add(cur.toString().toLowerCase(Locale.ROOT));
        }
        return new Query(terms, null);
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
        /**
         * The matched line, tidied for display, or empty when the title matched and the body
         * never mentioned it.
         *
         * <p>A result used to read "Aches, Medical-Health, line 6", which names the note and
         * then asks you to take the match on trust. The line itself is the one piece of a
         * result that answers why it is in the list.
         */
        public final String snippet;

        Hit(final File file, final String display, final String where,
            final long createdMillis, final boolean inBody, final int line,
            final String snippet) {
            this.file = file;
            this.display = display;
            this.where = where;
            this.createdMillis = createdMillis;
            this.inBody = inBody;
            this.line = line;
            this.snippet = snippet;
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
        final Query parsed = parse(query);
        if (parsed.isEmpty()) {
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
                // The body is read even when the title already matches. It used to be
                // skipped, which saved a read and cost the line number, so a title hit
                // dropped you at the top of the note with nothing highlighted.
                final Match m = findInFile(f, parsed, display.toLowerCase(Locale.ROOT));
                if (m == null) {
                    continue;
                }
                hits.add(new Hit(f, display, relative(rootPath, f.getParentFile()),
                        created(f, index), m.line >= 0, m.line, m.text));
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

    /** A file that satisfied the query, and the best line to open it at. */
    private static final class Match {
        final int line;
        final String text;

        Match(final int line, final String text) {
            this.line = line;
            this.text = text;
        }
    }

    /** Longest snippet worth putting on one line under a title. */
    private static final int SNIPPET_CHARS = 72;

    /**
     * Tidy a matched line for display: drop the leading markup that would otherwise be the
     * first thing you read, collapse runs of whitespace, and cut it short with an ellipsis.
     */
    static String snippet(final String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim()
                .replaceFirst("^#{1,6}\\s+", "")
                .replaceFirst("^>\\s*", "")
                .replaceFirst("^([-*+]|\\d+\\.)\\s+", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (t.length() > SNIPPET_CHARS) {
            t = t.substring(0, SNIPPET_CHARS).trim() + "\u2026";
        }
        return t;
    }

    /**
     * Whether this file satisfies every condition, and where to land if so.
     *
     * <p>Conditions may be spread across the title and several lines: a note titled "Fridge"
     * with "the blue one" in the body satisfies {@code blue fridge} without either half
     * matching alone. So the title is checked first, the body is scanned once, and a
     * condition is ticked off wherever it turns up.
     *
     * <p>The line chosen is the one satisfying the most conditions rather than the first to
     * satisfy any, because with several terms the first mention is usually the least
     * informative one. Ties go to the earlier line.
     */
    private static Match findInFile(final File f, final Query q, final String titleLower) {
        final Set<String> outstanding = new HashSet<>(q.terms);
        boolean regexSeen = false;

        if (q.regex != null) {
            regexSeen = q.regex.matcher(titleLower).find();
        } else {
            for (final String t : q.terms) {
                if (titleLower.contains(t)) {
                    outstanding.remove(t);
                }
            }
        }

        int bestLine = -1;
        int bestScore = 0;
        String bestText = "";

        if (f.length() <= MAX_BODY_BYTES) {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
                String line;
                int n = 0;
                while ((line = br.readLine()) != null) {
                    final String lower = line.toLowerCase(Locale.ROOT);
                    final int score = q.score(lower);
                    if (score > bestScore) {
                        bestScore = score;
                        bestLine = n;
                        bestText = line;
                    }
                    if (q.regex != null) {
                        regexSeen |= score > 0;
                    } else if (!outstanding.isEmpty()) {
                        for (final Iterator<String> it = outstanding.iterator(); it.hasNext(); ) {
                            if (lower.contains(it.next())) {
                                it.remove();
                            }
                        }
                    }
                    n++;
                }
            } catch (final Exception e) {
                // An unreadable note is not a match. Failing the whole search over one file
                // would be worse than missing it.
                return null;
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

        final boolean satisfied = (q.regex != null) ? regexSeen : outstanding.isEmpty();
        if (!satisfied) {
            return null;
        }
        return new Match(bestLine, snippet(bestText));
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
