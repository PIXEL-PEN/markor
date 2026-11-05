package net.pixelpen.journal.util;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * The single owner of every date format this app <em>stores</em>.
 * <p>
 * <b>Everything here is pinned to {@link Locale#ENGLISH} on purpose.</b> These strings become
 * folder names on disk and the first line of every note, so they are data, not presentation.
 * Formatting them with the device locale (which is what the code did before) meant a German
 * phone wrote {@code 05_Mai} while the folder matcher looked for {@code May}, and changing the
 * phone's language mid-year split one year across {@code 05_May} and {@code 06_Juni}.
 * <p>
 * Localise what is shown on screen. Never localise what is written to disk.
 * <p>
 * This class also <em>reads</em> the stamp back ({@link #parseStamp}), which is what makes
 * creation order survive rename, move, copy, reinstall and device migration: the evidence lives
 * inside the note rather than in an index keyed on a file path that can change.
 */
public final class NoteTimestamp {
    private NoteTimestamp() {
    }

    /**
     * The stamp written as line 1 of every note, e.g. {@code Wed. November 04, 2026 | 3:45 pm}.
     * This is the durable record of when an entry was created. Changing this pattern orphans
     * every note already written, so do not change it without a migration.
     */
    public static final String STAMP_PATTERN = "EEE. MMMM dd, yyyy | h:mm a";

    /**
     * An earlier variant that may exist in archives: unpadded day, and long weekday
     * abbreviations ({@code Tues.}, {@code Thurs.}) rather than the three-letter ones.
     * Kept here so the Phase 2b parser knows what else it may meet.
     */
    public static final String LEGACY_STAMP_PATTERN = "EEE. MMMM d, yyyy | h:mm a";

    /** Year folder, e.g. {@code 2026}. */
    /**
     * A month, with no day and no time: {@code November 2026}.
     *
     * <p>For entries that are honestly only known to the month. A year-end highlight is the case
     * this exists for: you know the new app happened late in the year and not in January, and you
     * do not know the Tuesday. Writing a full stamp for one would record a day and a time that
     * were never true, and a date that specific reads as fact to anything that comes along later.
     */
    public static final String MONTH_STAMP_PATTERN = "MMMM yyyy";

    public static final String YEAR_FOLDER_PATTERN = "yyyy";

    /** Month folder, e.g. {@code 11_November}. */
    public static final String MONTH_FOLDER_PATTERN = "MM_MMMM";

    /**
     * Combines the day the user picked on the calendar with the current wall-clock time.
     * The picked date decides which year/month folder the note lands in; the current time
     * gives entries made on the same day a stable order.
     */
    public static Calendar combineSelectedDateWithCurrentTime(final Calendar picked) {
        final TimeZone tz = TimeZone.getDefault();
        final Calendar now = Calendar.getInstance(tz);

        final Calendar combined = Calendar.getInstance(tz);
        combined.clear();
        combined.set(
                picked.get(Calendar.YEAR),
                picked.get(Calendar.MONTH),
                picked.get(Calendar.DAY_OF_MONTH),
                now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE),
                0
        );
        return combined;
    }

    /** The stamp for line 1 of a note. */
    public static String format(final Calendar c) {
        return formatWith(STAMP_PATTERN, c).replace(" AM", " am").replace(" PM", " pm");
    }

    /** Convenience for epoch millis from {@code CalendarView.getDate()}. */
    public static String fromSelectedEpochMillis(final long selectedDayUtcMillis) {
        final Calendar picked = Calendar.getInstance(TimeZone.getDefault());
        picked.setTimeInMillis(selectedDayUtcMillis);
        return format(combineSelectedDateWithCurrentTime(picked));
    }

    /** Combines picked date with current time, then formats. */
    public static String fromCalendar(final Calendar picked) {
        return format(combineSelectedDateWithCurrentTime(picked));
    }

    /** Year folder name, e.g. {@code 2026}. */
    public static String yearFolderName(final Calendar c) {
        return formatWith(YEAR_FOLDER_PATTERN, c);
    }

    /** Month folder name, e.g. {@code 11_November}. */
    public static String monthFolderName(final Calendar c) {
        return formatWith(MONTH_FOLDER_PATTERN, c);
    }

    /** Month folder name for "today", used as the Tree's default expansion target. */
    public static String currentMonthFolderName() {
        return monthFolderName(Calendar.getInstance(TimeZone.getDefault()));
    }

    private static String formatWith(final String pattern, final Calendar c) {
        final SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.ENGLISH);
        sdf.setTimeZone(c.getTimeZone() != null ? c.getTimeZone() : TimeZone.getDefault());
        return sdf.format(c.getTime());
    }

    // ---------------------------------------------------------------- parsing

    /**
     * Weekday tokens we accept and discard. The weekday is redundant — the date determines it —
     * and it is the one part that differs between the stamp formats this archive may contain
     * ({@code Wed.} from the current writer, {@code Tues.} / {@code Thurs.} from the earlier
     * one). Rather than try to teach SimpleDateFormat all the spellings, we drop it.
     */
    private static final Set<String> WEEKDAY_TOKENS = new HashSet<>(Arrays.asList(
            "mon", "monday",
            "tue", "tues", "tuesday",
            "wed", "weds", "wednesday",
            "thu", "thur", "thurs", "thursday",
            "fri", "friday",
            "sat", "saturday",
            "sun", "sunday"
    ));

    /**
     * Date patterns tried in order, after the weekday has been removed. {@code d} parses both
     * "4" and "04", so one pattern covers the padded and unpadded forms.
     */
    private static final String[] PARSE_PATTERNS = {
            "MMMM d, yyyy | h:mm a",   // the stamp as written, e.g. "November 04, 2026 | 3:45 pm"
            "MMMM d, yyyy | H:mm",     // tolerated: 24-hour, in case a device ever produced it
            "MMMM d, yyyy",            // tolerated: date with no time; sorts to midnight
    };

    /**
     * Reads a creation instant back out of line 1 of a note.
     * <p>
     * Returns {@code null} for anything it does not recognise — a heading, body text, an empty
     * line, a note written before stamping existed. "No stamp" is a normal outcome, not an
     * error, and the caller falls back to the next source of truth.
     * <p>
     * The stamp carries no timezone, so it is interpreted in the device's current zone, the same
     * zone it was written in. Moving timezones shifts every note equally, so relative ordering —
     * the thing that actually matters here — is unaffected.
     *
     * @param line the first line of a note, or null
     * @return epoch millis, or null if the line carries no recognisable stamp
     */
    /** A month stamp for the given date: {@code November 2026}. */
    public static String formatMonth(final java.util.Calendar c) {
        return new java.text.SimpleDateFormat(MONTH_STAMP_PATTERN, Locale.ENGLISH)
                .format(c.getTime());
    }

    /**
     * The date a note's first line says it is about, whether that line carries a full stamp or
     * only a month.
     *
     * <p>One entry point for the question, so callers do not have to know which kinds of stamp
     * exist. A month resolves to its first instant, which is what puts a month-only entry ahead of
     * everything dated later in that month and after everything in the month before.
     */
    public static Long parseAboutDate(final String line) {
        final Long exact = parseStamp(line);
        if (exact != null) {
            return exact;
        }
        return parseMonthStamp(line);
    }

    /** {@code November 2026} to the first instant of that month, or null. */
    public static Long parseMonthStamp(final String line) {
        if (line == null) {
            return null;
        }
        final String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        final java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat(MONTH_STAMP_PATTERN, Locale.ENGLISH);
        fmt.setLenient(false);
        final java.text.ParsePosition pos = new java.text.ParsePosition(0);
        final java.util.Date parsed = fmt.parse(trimmed, pos);
        // Must account for the whole line, or "November 2026 was hard" would read as a stamp.
        if (parsed == null || pos.getIndex() != trimmed.length()) {
            return null;
        }
        return parsed.getTime();
    }

    public static Long parseStamp(final String line) {
        if (line == null) {
            return null;
        }
        String s = line.trim();
        if (s.isEmpty()) {
            return null;
        }

        // Strip a leading weekday token if there is one. Matching against an explicit set
        // matters: a naive "drop the first word" would eat the month from "November 04, 2026".
        final int firstSpace = s.indexOf(' ');
        if (firstSpace > 0) {
            final String head = s.substring(0, firstSpace).replace(".", "").replace(",", "")
                    .toLowerCase(Locale.ROOT);
            if (WEEKDAY_TOKENS.contains(head)) {
                s = s.substring(firstSpace + 1).trim();
            }
        }

        // SimpleDateFormat with Locale.ENGLISH expects "PM"; we write "pm".
        s = s.replace(" am", " AM").replace(" pm", " PM");
        // Be forgiving about spacing around the separator and between fields.
        s = s.replaceAll("\\s+", " ");

        for (final String pattern : PARSE_PATTERNS) {
            final Long parsed = tryParse(s, pattern);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static Long tryParse(final String s, final String pattern) {
        final SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.ENGLISH);
        sdf.setTimeZone(TimeZone.getDefault());
        // Strict: without this, "February 31" silently becomes March 3rd, and junk lines
        // parse into plausible-looking dates.
        sdf.setLenient(false);

        final ParsePosition pos = new ParsePosition(0);
        final Date d = sdf.parse(s, pos);
        if (d == null) {
            return null;
        }
        // Require the whole line to be consumed. Otherwise a body line that merely *starts*
        // with something date-shaped would be accepted as a stamp.
        if (s.substring(pos.getIndex()).trim().length() > 0) {
            return null;
        }
        return d.getTime();
    }

    /** True if this line is a stamp we can read. Convenience for callers that only need a test. */
    public static boolean isStamp(final String line) {
        return parseStamp(line) != null;
    }
}
