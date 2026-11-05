package net.pixelpen.journal.model;

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

    /** Highlight slots, filled or not. The bar always shows this many. */
    public static final int HIGHLIGHT_SLOTS = 3;

    /** One note in a month. */
    public static final class Event {
        public final File file;
        public final String title;
        public final String category;

        Event(final File file, final String title, final String category) {
            this.file = file;
            this.title = title;
            this.category = category;
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
        if (root == null || !root.isDirectory()) {
            return null;
        }
        final File[] kids = root.listFiles();
        if (kids == null) {
            return null;
        }
        File best = null;
        for (final File f : kids) {
            if (f.isDirectory() && f.getName().matches("\\d{4}")
                    && (best == null || f.getName().compareTo(best.getName()) > 0)) {
                best = f;
            }
        }
        return best;
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
            final List<Event> events = new ArrayList<>();
            for (final String cat : cats) {
                final File catDir = new File(monthDir, cat);
                final File[] notes = catDir.listFiles();
                if (notes == null) {
                    continue;
                }
                final List<File> sorted = new ArrayList<>();
                for (final File n : notes) {
                    if (n.isFile() && isNote(n.getName())) {
                        sorted.add(n);
                    }
                }
                Collections.sort(sorted, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (final File n : sorted) {
                    events.add(new Event(n, stripExtension(n.getName()), cat));
                }
            }
            months.add(new Month(monthDir.isDirectory() ? monthDir : null,
                    displayName(folder), events));
        }

        final List<String> categories = new ArrayList<>();
        categories.add(ALL_CATEGORIES);
        categories.addAll(cats);

        return new YearOverview(year, yearDir, months, categories, readHighlights(yearDir, year));
    }

    /**
     * The year's highlight notes, at most {@link #HIGHLIGHT_SLOTS} of them.
     *
     * <p>"Highlights 2026" beside the month folders, as year.py named it. The bar shows three
     * slots whether or not the folder exists, so an absent folder is not an error here.
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
        Collections.sort(notes, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (final File f : notes) {
            if (out.size() >= HIGHLIGHT_SLOTS) {
                break;
            }
            out.add(new Event(f, stripExtension(f.getName()), "Highlights"));
        }
        return out;
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
