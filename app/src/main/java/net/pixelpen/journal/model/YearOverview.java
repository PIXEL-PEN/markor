package net.pixelpen.journal.model;

import net.pixelpen.journal.util.NoteTimestamp;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * A year of the archive, read for the overview screen.
 *
 * <p>The same scan year.py did before generating its HTML: the twelve month folders of the latest
 * year, the set of categories appearing anywhere in them, the notes in each month grouped by
 * category, and the contents of the "Highlights &lt;year&gt;" folder.
 *
 * <p>Whole-year by nature, so it is read once off the main thread and held, rather than a
 * directory at a time like {@link TreeStore}. A year is a bounded amount of work; the screen shows
 * all of it at once and there is nothing to defer.
 */
public final class YearOverview {

    /** Folder names as the archive writes them, in order. */
    public static final String[] MONTH_FOLDERS = {
            "01_January", "02_February", "03_March", "04_April", "05_May", "06_June",
            "07_July", "08_August", "09_September", "10_October", "11_November", "12_December",
    };

    /** The category selected when the screen opens, as in the original. */
    public static final String DEFAULT_CATEGORY = "Key Events";

    /** Shown in the dropdown above the individual categories. */
    public static final String ALL_CATEGORIES = "All";

    /**
     * Empty highlight slots the bar offers. A floor, not a cap: more than this many highlights are
     * all shown, because hiding one you wrote would be worse than a longer strip.
     */
    public static final int HIGHLIGHT_SLOTS = 3;

    /** One note in a month. */
    public static final class Event {
        public final File file;
        public final String title;
        public final String category;
        /**
         * When this is about, from its first line, or 0 when it carries no readable stamp. Only
         * filled for highlights, where it decides the order and is worth showing: a strip sorted
         * by something invisible looks arbitrary.
         */
        public final long aboutMillis;

        Event(final File file, final String title, final String category) {
            this(file, title, category, 0L);
        }

        Event(final File file, final String title, final String category, final long aboutMillis) {
            this.file = file;
            this.title = title;
            this.category = category;
            this.aboutMillis = aboutMillis;
        }
    }

    /** One month box. */
    public static final class Month {
        /** Folder name, e.g. {@code 11_November}; null when the month has no folder. */
        public final File dir;
        /** What the box is headed with, e.g. {@code November}. */
        public final String display;
        public final List<Event> events;

        Month(final File dir, final String display, final List<Event> events) {
            this.dir = dir;
            this.display = display;
            this.events = events;
        }

        /** Events in this category, or all of them when the category is {@link #ALL_CATEGORIES}. */
        public List<Event> eventsIn(final String category) {
            if (ALL_CATEGORIES.equals(category)) {
                return events;
            }
            final List<Event> out = new ArrayList<>();
            for (final Event e : events) {
                if (e.category.equals(category)) {
                    out.add(e);
                }
            }
            return out;
        }
    }

    public final String year;
    public final File yearDir;
    public final List<Month> months;
    /** Categories found anywhere in the year, with "All" first. */
    public final List<String> categories;
    /** Up to {@link #HIGHLIGHT_SLOTS} highlight notes; may be shorter, and may be empty. */
    public final List<Event> highlights;

    private YearOverview(final String year, final File yearDir, final List<Month> months,
                         final List<String> categories, final List<Event> highlights) {
        this.year = year;
        this.yearDir = yearDir;
        this.months = months;
        this.categories = categories;
        this.highlights = highlights;
    }

    /** An overview with no year folder to read, so the screen has something to render. */
    public static YearOverview empty(final String year) {
        final List<Month> months = new ArrayList<>();
        for (final String folder : MONTH_FOLDERS) {
            months.add(new Month(null, displayName(folder), Collections.<Event>emptyList()));
        }
        final List<String> cats = new ArrayList<>();
        cats.add(ALL_CATEGORIES);
        return new YearOverview(year, null, months, cats, Collections.<Event>emptyList());
    }

    /**
     * The most recent year folder under {@code root}, or null if there is none.
     *
     * <p>Only all-digit names count, so "Highlights 2026" is not mistaken for a year.
     */
    public static File latestYearDir(final File root) {
        final List<File> years = yearDirs(root);
        return years.isEmpty() ? null : years.get(years.size() - 1);
    }

    /**
     * Every year folder under the archive root, oldest first.
     *
     * <p>Only all-digit names count, so "Highlights 2026" is not mistaken for a year.
     */
    public static List<File> yearDirs(final File root) {
        final List<File> out = new ArrayList<>();
        if (root == null || !root.isDirectory()) {
            return out;
        }
        final File[] kids = root.listFiles();
        if (kids == null) {
            return out;
        }
        for (final File f : kids) {
            if (f.isDirectory() && f.getName().matches("\\d{4}")) {
                out.add(f);
            }
        }
        Collections.sort(out, (a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    /** Read a year. Call off the main thread. */
    public static YearOverview read(final File yearDir) {
        if (yearDir == null || !yearDir.isDirectory()) {
            return empty("");
        }
        final String year = yearDir.getName();

        // Every category appearing in any month, so the dropdown is the same whichever month is
        // being looked at. Sorted, with "All" put in front afterwards.
        final TreeSet<String> cats = new TreeSet<>();
        for (final String folder : MONTH_FOLDERS) {
            final File monthDir = new File(yearDir, folder);
            final File[] subs = monthDir.listFiles();
            if (subs == null) {
                continue;
            }
            for (final File s : subs) {
                if (s.isDirectory() && !s.getName().startsWith(".")) {
                    cats.add(s.getName());
                }
            }
        }

        final List<Month> months = new ArrayList<>();
        for (final String folder : MONTH_FOLDERS) {
            final File monthDir = new File(yearDir, folder);
            // Every category's notes into one list, then ordered once.
            //
            // This used to sort inside each category and append them category after category,
            // so a month read as all of General oldest-first, then all of Work, then the rest.
            // Grouped by folder and alphabetical within it, which from the outside looks like no
            // order at all. The Tree puts newest first because that is the order this archive
            // exists to preserve, and the Year screen showing the same notes in a different
            // sequence is just two answers to one question.
            final List<Event> events = new ArrayList<>();
            for (final String cat : cats) {
                final File catDir = new File(monthDir, cat);
                final File[] notes = catDir.listFiles();
                if (notes == null) {
                    continue;
                }
                for (final File n : notes) {
                    if (n.isFile() && isNote(n.getName())) {
                        final Long about = stampOf(n);
                        events.add(new Event(n, stripExtension(n.getName()), cat,
                                (about != null) ? about : 0L));
                    }
                }
            }
            // Newest first. A note with no readable stamp sorts last rather than to the top,
            // where a zero would otherwise put it; name is the tiebreak so two notes written in
            // the same minute keep a stable order between draws.
            Collections.sort(events, (a, b) -> {
                if (a.aboutMillis != b.aboutMillis) {
                    if (a.aboutMillis == 0L) {
                        return 1;
                    }
                    if (b.aboutMillis == 0L) {
                        return -1;
                    }
                    return Long.compare(b.aboutMillis, a.aboutMillis);
                }
                return a.file.getName().compareToIgnoreCase(b.file.getName());
            });
            months.add(new Month(monthDir.isDirectory() ? monthDir : null,
                    displayName(folder), events));
        }

        final List<String> categories = new ArrayList<>();
        categories.add(ALL_CATEGORIES);
        categories.addAll(cats);

        return new YearOverview(year, yearDir, months, categories, readHighlights(yearDir, year));
    }

    /**
     * The year's highlight notes, oldest first by the date each one is about.
     *
     * <p>"Highlights 2026" beside the month folders, as year.py named it. The bar shows its slots
     * whether or not the folder exists, so an absent folder is not an error here.
     *
     * <p>Ordered by the stamp on line 1, not by filename and not by when the file was written.
     * Highlights are a year-end exercise: you write them once the year has a shape, so the one
     * typed first is usually not the one that happened first. The stamp carries the date the
     * highlight is about -- the same convention a backdated entry on the Calendar screen uses --
     * so reading it puts them in the order the year actually went. A highlight with no readable
     * stamp sorts last rather than being dropped, with the name as the tiebreak throughout so the
     * order does not shuffle between reads.
     */
    private static List<Event> readHighlights(final File yearDir, final String year) {
        final List<Event> out = new ArrayList<>();
        final File dir = new File(yearDir, "Highlights " + year);
        final File[] files = dir.listFiles();
        if (files == null) {
            return out;
        }
        final List<File> notes = new ArrayList<>();
        for (final File f : files) {
            if (f.isFile() && isNote(f.getName())) {
                notes.add(f);
            }
        }
        Collections.sort(notes, (a, b) -> {
            final Long sa = stampOf(a);
            final Long sb = stampOf(b);
            if (sa != null && sb != null && !sa.equals(sb)) {
                return Long.compare(sa, sb);
            }
            if (sa == null && sb != null) {
                return 1;
            }
            if (sa != null && sb == null) {
                return -1;
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (final File f : notes) {
            final Long about = stampOf(f);
            out.add(new Event(f, stripExtension(f.getName()), "Highlights",
                    (about != null) ? about : 0L));
        }
        return out;
    }

    /**
     * The date a note is about, from its first line, or null if it carries no readable stamp.
     * Accepts a full stamp or a month-only one, so highlights and dated notes sort together.
     */
    private static Long stampOf(final File f) {
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            return NoteTimestamp.parseAboutDate(br.readLine());
        } catch (final Exception e) {
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

    /**
     * The year's personal-growth note, whether or not it exists yet.
     *
     * <p>A single file beside the month folders rather than a folder of notes, because this is
     * one piece of prose for the year: achievements, what was learned, what is hoped for. The
     * highlights are the enumerable part and already have their own folder.
     */
    public File growthFile() {
        return (yearDir == null) ? null : new File(yearDir, "Growth " + year + ".md");
    }

    /** The folder for this year's highlights, whether or not it exists yet. */
    public File highlightsDir() {
        return (yearDir == null) ? null : new File(yearDir, "Highlights " + year);
    }

    /** {@code 11_November} to {@code November}. */
    private static String displayName(final String folder) {
        final int us = folder.indexOf('_');
        return (us >= 0) ? folder.substring(us + 1) : folder;
    }

    private static boolean isNote(final String name) {
        final String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".md") || n.endsWith(".markdown") || n.endsWith(".txt");
    }

    private static String stripExtension(final String name) {
        final int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }
}
