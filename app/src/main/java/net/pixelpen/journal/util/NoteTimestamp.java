package net.pixelpen.journal.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
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
 * TODO (Phase 2b): add {@code parse(String)} here, reading the stamp back off line 1 of a note
 * so creation order survives rename, move, reinstall and device migration. It must accept BOTH
 * {@link #STAMP_PATTERN} and {@link #LEGACY_STAMP_PATTERN} (see below), and must treat an
 * unparseable line as "no stamp" rather than as an error.
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
}
