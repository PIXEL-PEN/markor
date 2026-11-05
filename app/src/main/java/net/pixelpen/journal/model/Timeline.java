package net.pixelpen.journal.model;

import net.pixelpen.journal.util.NoteTimestamp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A life timeline: years, what happened in them, and the one thing that stood out.
 *
 * <p>Lives in {@code Timeline/} under the home folder, one folder per year:
 *
 * <pre>
 *   Timeline/
 *     2001/
 *       2001.md        the year's entry -- a stamp, then bullets
 *       highlight.md   the one line the year is remembered by
 *       Images/        pictures from that year
 *       Documents/     anything else kept from it
 * </pre>
 *
 * <p>Decades are not stored. "1950s" is worked out from which year folders exist, so there is only
 * one hierarchy to keep straight and adding 1973 years from now simply works rather than needing a
 * decade created to hold it.
 *
 * <p>A year folder with nothing in it is meaningful: it is a year laid down in the skeleton and
 * not yet written about. That is the normal state of most of a timeline for a long time -- going
 * backwards is done in evenings, a year at a time, as things are remembered -- so an empty year is
 * shown rather than hidden.
 */
public final class Timeline {

    /** Folder under the home folder. */
    public static final String FOLDER = "Timeline";

    /** One year. */
    public static final class Year {
        public final int year;
        public final File dir;
        /** The year's entry, which may not exist yet. */
        public final File notesFile;
        /** Bullet lines, stripped of their markers; empty when nothing is written. */
        public final List<String> bullets;
        /** The highlight line, or null. */
        public final String highlight;
        public final File highlightFile;
        public final int imageCount;
        public final int documentCount;

        Year(final int year, final File dir, final File notesFile, final List<String> bullets,
             final String highlight, final File highlightFile,
             final int imageCount, final int documentCount) {
            this.year = year;
            this.dir = dir;
            this.notesFile = notesFile;
            this.bullets = bullets;
            this.highlight = highlight;
            this.highlightFile = highlightFile;
            this.imageCount = imageCount;
            this.documentCount = documentCount;
        }

        public boolean hasNotes() {
            return !bullets.isEmpty();
        }

        /** The decade this year falls in, as a number: 1973 becomes 1970. */
        public int decade() {
            return (year / 10) * 10;
        }
    }

    public final File dir;
    /** Every year found, ascending. */
    public final List<Year> years;

    private Timeline(final File dir, final List<Year> years) {
        this.dir = dir;
        this.years = years;
    }

    /** The timeline folder under the given home folder, whether or not it exists. */
    public static File folderIn(final File home) {
        return (home == null) ? null : new File(home, FOLDER);
    }

    /** Read the timeline. Call off the main thread. */
    public static Timeline read(final File timelineDir) {
        final List<Year> years = new ArrayList<>();
        if (timelineDir == null || !timelineDir.isDirectory()) {
            return new Timeline(timelineDir, years);
        }
        final File[] kids = timelineDir.listFiles();
        if (kids == null) {
            return new Timeline(timelineDir, years);
        }
        for (final File f : kids) {
            if (!f.isDirectory() || !f.getName().matches("\\d{4}")) {
                continue;
            }
            years.add(readYear(f, Integer.parseInt(f.getName())));
        }
        Collections.sort(years, (a, b) -> Integer.compare(a.year, b.year));
        return new Timeline(timelineDir, years);
    }

    private static Year readYear(final File dir, final int year) {
        final File notes = new File(dir, year + ".md");
        final File highlight = new File(dir, "highlight.md");
        return new Year(year, dir, notes,
                readBullets(notes), readFirstContentLine(highlight), highlight,
                countIn(new File(dir, "Images")), countIn(new File(dir, "Documents")));
    }

    /**
     * The year's bullets.
     *
     * <p>Markdown list markers are stripped, because the screen draws its own; a line that is not
     * a list item is kept as written rather than dropped, so an entry someone typed as prose still
     * shows up instead of vanishing for not being formatted correctly.
     */
    private static List<String> readBullets(final File f) {
        final List<String> out = new ArrayList<>();
        if (!f.isFile()) {
            return out;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                final String t = line.trim();
                if (first) {
                    first = false;
                    // Line one is the stamp, as everywhere else in the archive.
                    if (NoteTimestamp.parseAboutDate(t) != null) {
                        continue;
                    }
                }
                if (t.isEmpty()) {
                    continue;
                }
                out.add(t.replaceFirst("^([-*+]|\\d+\\.)\\s+", ""));
            }
        } catch (final Exception e) {
            // An unreadable year shows as an empty one rather than taking the screen down.
        } finally {
            close(br);
        }
        return out;
    }

    /** First line that is not the stamp and not blank. */
    private static String readFirstContentLine(final File f) {
        if (!f.isFile()) {
            return null;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                final String t = line.trim();
                if (first) {
                    first = false;
                    if (NoteTimestamp.parseAboutDate(t) != null) {
                        continue;
                    }
                }
                if (!t.isEmpty()) {
                    return t.replaceFirst("^#+\\s*", "").replaceFirst("^([-*+])\\s+", "");
                }
            }
        } catch (final Exception e) {
            return null;
        } finally {
            close(br);
        }
        return null;
    }

    private static int countIn(final File dir) {
        final File[] kids = dir.listFiles();
        if (kids == null) {
            return 0;
        }
        int n = 0;
        for (final File f : kids) {
            if (f.isFile() && !f.getName().startsWith(".")) {
                n++;
            }
        }
        return n;
    }

    private static void close(final BufferedReader br) {
        try {
            if (br != null) {
                br.close();
            }
        } catch (final Exception ignore) {
            // Nothing useful to do if a read-only handle will not close.
        }
    }

    /** {@code 1950} to {@code 1950s}. */
    public static String decadeLabel(final int decade) {
        return decade + "s";
    }

    /** True if the name is a four-digit year this screen would accept. */
    public static boolean isYear(final String s) {
        return s != null && s.trim().matches("\\d{4}");
    }

    /** An image this screen can show as a thumbnail. */
    public static boolean isImage(final String name) {
        final String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".webp") || n.endsWith(".gif") || n.endsWith(".bmp");
    }
}
