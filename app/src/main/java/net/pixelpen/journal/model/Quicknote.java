/*#######################################################
 *
 *   Autumn
 *   Copyright (C) 2026 PIXEL-PEN
 *   Licensed under the GNU General Public License v3.0
 *
#########################################################*/
package net.pixelpen.journal.model;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Quicknotes: one file per month, outside the category tree.
 *
 * <p>A journal entry earns its own file. A quicknote does not -- it is the thing you bought,
 * the person who served you, the fact you will want next week and never again. Giving each one
 * a file would bury the archive in trivia, so a month's worth share a document and are
 * separated by a rule.
 *
 * <p>Deliberately not a category. Categories are the shape of the journal, chosen on purpose,
 * and a quicknote is the opposite of a considered filing decision. They live in
 * {@code Quicknotes/<year>/} next to {@code Images/} and {@code Files/}, so nothing that walks
 * the categories sees them. Keep it that way: if quicknotes start appearing in the Year
 * overview, the overview stops being a year in review and becomes a log.
 *
 * <p>Newest first. The file is read top-down and the thing you just wrote is the thing you
 * want to see, so an entry is inserted directly under the month heading rather than appended.
 */
public final class Quicknote {

    /** Folder under the archive home. Not a category; see the class comment. */
    public static final String FOLDER = "Quicknotes";

    /**
     * Stamp written above each entry. Pinned to {@link Locale#ENGLISH} for the same reason
     * the note timestamp is: this string goes to disk, which makes it data rather than
     * presentation, and a device-locale stamp cannot be read back reliably.
     */
    private static final String STAMP = "EEE d MMM yyyy, HH:mm";

    private static final String MONTH_TITLE = "MMMM yyyy";
    private static final String MONTH_FILE = "yyyy-MM MMMM";

    private Quicknote() {
    }

    /**
     * The file a quicknote written on this date belongs in:
     * {@code <home>/Quicknotes/<year>/<yyyy-MM MonthName>.md}.
     *
     * <p>The year folder keeps a long-lived archive navigable, and the numeric prefix sorts
     * chronologically while the month name keeps it readable outside the app.
     */
    public static File fileFor(final File home, final Calendar when) {
        if (home == null || when == null) {
            return null;
        }
        final String year = String.valueOf(when.get(Calendar.YEAR));
        final String name = new SimpleDateFormat(MONTH_FILE, Locale.ENGLISH).format(when.getTime());
        return new File(new File(new File(home, FOLDER), year), name + ".md");
    }

    /**
     * Write one quicknote, newest first, creating the month's file on first use.
     *
     * @return the file written, or null if nothing was written.
     */
    public static File write(final File home, final Calendar when, final String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        final File file = fileFor(home, when);
        if (file == null) {
            return null;
        }
        final File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }

        final String heading = "# " + new SimpleDateFormat(MONTH_TITLE, Locale.ENGLISH)
                .format(when.getTime());
        final String stamp = new SimpleDateFormat(STAMP, Locale.ENGLISH).format(when.getTime());
        final String entry = "---\n\n## " + stamp + "\n\n" + text.trim() + "\n\n";

        // Keep whatever is already there, minus its heading line, and put the new entry above
        // it. Reading the heading back rather than assuming it means a file the user has
        // retitled by hand is not silently given a second heading.
        String body = "";
        if (file.isFile()) {
            try {
                final String existing = read(file);
                final int nl = existing.indexOf('\n');
                body = (existing.startsWith("#") && nl >= 0)
                        ? existing.substring(nl + 1)
                        : existing;
                body = body.replaceFirst("^\\s+", "");
            } catch (final IOException e) {
                // An unreadable file is not worth losing the note over, but it is absolutely
                // not worth overwriting either.
                return null;
            }
        }

        try {
            write(file, heading + "\n\n" + entry + body);
        } catch (final IOException e) {
            return null;
        }
        return file;
    }

    private static String read(final File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        }
    }

    private static void write(final File f, final String s) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes("UTF-8"));
        }
    }
}
