/*#######################################################
 *
 *   Autumn - journal, archive, timeline
 *   Maintained 2026 by PIXEL-PEN
 *   License: Apache 2.0
 *
#########################################################*/
package net.pixelpen.journal.view;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import net.pixelpen.journal.R;

/**
 * A compact month grid, in place of Android's {@link android.widget.CalendarView}.
 *
 * <p>The stock widget was swapped out because almost nothing about it can be changed. On API 21
 * and up it renders in Material mode, where {@code dateTextAppearance}, {@code
 * weekDayTextAppearance} and the various colour attributes are quietly ignored -- they only ever
 * applied to the old Holo mode. It sizes itself from its own internal metrics, so it cannot be
 * made denser or shorter; the one hook that still works is {@code colorAccent}, which is why the
 * selected day was the same red as the FAB. And it has no way at all to mark a day, which is the
 * one thing a journal's calendar actually needs.
 *
 * <p>So: seven columns of plain text views, bound to a {@link Calendar}. Roughly half the height
 * of the stock widget, and every pixel of it is ours.
 */
public class MonthGrid extends LinearLayout {

    /** Weeks drawn. Six covers every month layout, including a 31-day month starting Sunday. */
    private static final int WEEKS = 6;
    private static final int DAYS_IN_WEEK = 7;

    /** Height of one day cell. The stock widget used roughly double this. */
    private static final int CELL_HEIGHT_DP = 38;

    public interface OnDateSelectedListener {
        void onDateSelected(Calendar date);
    }

    /** Called when the displayed month changes, so the host can refresh its marks. */
    public interface OnMonthChangedListener {
        void onMonthChanged(int year, int month);
    }

    private TextView _titleView;
    private final TextView[] _cells = new TextView[WEEKS * DAYS_IN_WEEK];
    private final Calendar _shown = Calendar.getInstance();
    private final Calendar _selected = Calendar.getInstance();

    /** Days of the displayed month that have something written on them. */
    private final Set<Integer> _marked = new HashSet<>();

    private OnDateSelectedListener _dateListener;
    private OnMonthChangedListener _monthListener;

    public MonthGrid(final Context context) {
        this(context, null);
    }

    public MonthGrid(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        build(context);
        bind();
    }

    // ----------------------------------------------------------------- build

    private void build(final Context context) {
        final float d = getResources().getDisplayMetrics().density;

        // --- title row: ‹ November 2026 › ---
        final LinearLayout head = new LinearLayout(context);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final ImageButton prev = arrow(context, R.drawable.ic_chevron_left_24,
                R.string.previous_month);
        prev.setOnClickListener(v -> step(-1));

        _titleView = new TextView(context);
        final LinearLayout.LayoutParams titleLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        _titleView.setLayoutParams(titleLp);
        _titleView.setGravity(Gravity.CENTER);
        _titleView.setTextSize(16);
        _titleView.setTypeface(Typeface.DEFAULT_BOLD);
        _titleView.setTextColor(ContextCompat.getColor(context, R.color.cal_title));
        // Tapping the month name returns to today, which is where someone who has scrolled three
        // months back almost always wants to get back to.
        _titleView.setOnClickListener(v -> goToToday());

        final ImageButton next = arrow(context, R.drawable.ic_chevron_right_24,
                R.string.next_month);
        next.setOnClickListener(v -> step(1));

        head.addView(prev);
        head.addView(_titleView);
        head.addView(next);
        addView(head);

        // --- weekday labels ---
        final LinearLayout days = new LinearLayout(context);
        days.setOrientation(HORIZONTAL);
        days.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final String[] initials = weekdayInitials();
        for (int i = 0; i < DAYS_IN_WEEK; i++) {
            final TextView t = new TextView(context);
            t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            t.setGravity(Gravity.CENTER);
            t.setTextSize(11);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setLetterSpacing(0.08f);
            t.setTextColor(ContextCompat.getColor(context, R.color.cal_weekday));
            t.setText(initials[i]);
            t.setPadding(0, Math.round(6 * d), 0, Math.round(6 * d));
            days.addView(t);
        }
        addView(days);

        // --- day cells ---
        final int cell = Math.round(CELL_HEIGHT_DP * d);
        for (int w = 0; w < WEEKS; w++) {
            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cell));
            for (int c = 0; c < DAYS_IN_WEEK; c++) {
                final TextView t = new TextView(context);
                t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                t.setGravity(Gravity.CENTER);
                t.setTextSize(15);
                final int index = w * DAYS_IN_WEEK + c;
                t.setOnClickListener(v -> pick(index));
                _cells[index] = t;
                row.addView(t);
            }
            addView(row);
        }
    }

    private ImageButton arrow(final Context context, final int icon, final int description) {
        final float d = getResources().getDisplayMetrics().density;
        final ImageButton b = new ImageButton(context);
        final int side = Math.round(40 * d);
        b.setLayoutParams(new LinearLayout.LayoutParams(side, side));
        b.setImageResource(icon);
        b.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        final int ripple = outValue(android.R.attr.selectableItemBackgroundBorderless);
        if (ripple != 0) {
            // 0 means the theme did not supply one; setBackgroundResource(0) would throw.
            b.setBackgroundResource(ripple);
        }
        b.setContentDescription(getContext().getString(description));
        return b;
    }

    private int outValue(final int attr) {
        final android.util.TypedValue tv = new android.util.TypedValue();
        getContext().getTheme().resolveAttribute(attr, tv, true);
        return tv.resourceId;
    }

    /**
     * Weekday initials starting Monday, taken from the device's own locale.
     *
     * <p>Not hard-coded: the row has to agree with the grid below it, and the grid starts its
     * week on Monday.
     */
    private String[] weekdayInitials() {
        final String[] out = new String[DAYS_IN_WEEK];
        final SimpleDateFormat fmt = new SimpleDateFormat("EEEEE", Locale.getDefault());
        final Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
        for (int i = 0; i < DAYS_IN_WEEK; i++) {
            out[i] = fmt.format(c.getTime()).toUpperCase(Locale.getDefault());
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return out;
    }

    // ------------------------------------------------------------------ bind

    /** Redraw the grid for whatever month {@link #_shown} is in. */
    private void bind() {
        final Calendar c = (Calendar) _shown.clone();
        c.set(Calendar.DAY_OF_MONTH, 1);

        _titleView.setText(new SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                .format(c.getTime()));

        // Monday is column 0. Calendar.MONDAY is 2, so this maps Sunday (1) to 6 and the rest
        // down by two.
        final int firstColumn = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        final int daysInMonth = c.getActualMaximum(Calendar.DAY_OF_MONTH);

        final Calendar today = Calendar.getInstance();
        final boolean showingThisMonth = sameMonth(c, today);
        final boolean showingSelected = sameMonth(c, _selected);
        final int selectedDay = _selected.get(Calendar.DAY_OF_MONTH);
        final int todayDay = today.get(Calendar.DAY_OF_MONTH);

        for (int i = 0; i < _cells.length; i++) {
            final TextView t = _cells[i];
            final int day = i - firstColumn + 1;

            if (day < 1 || day > daysInMonth) {
                // Days either side are left blank rather than greyed. A journal's month is the
                // month; a dimmed 31st of October under November invites a mis-tap for no gain.
                t.setText("");
                t.setBackground(null);
                t.setClickable(false);
                continue;
            }

            t.setText(String.valueOf(day));
            t.setClickable(true);

            if (showingSelected && day == selectedDay) {
                t.setBackgroundResource(R.drawable.cal_day_selected);
                t.setTextColor(ContextCompat.getColor(getContext(), R.color.cal_day_selected_text));
                t.setTypeface(Typeface.DEFAULT_BOLD);
            } else if (showingThisMonth && day == todayDay) {
                t.setBackgroundResource(R.drawable.cal_day_today);
                t.setTextColor(ContextCompat.getColor(getContext(), R.color.cal_accent));
                t.setTypeface(Typeface.DEFAULT_BOLD);
            } else if (_marked.contains(day)) {
                t.setBackgroundResource(R.drawable.cal_day_marked);
                t.setTextColor(ContextCompat.getColor(getContext(), R.color.cal_day));
                t.setTypeface(Typeface.DEFAULT);
            } else {
                t.setBackground(null);
                t.setTextColor(ContextCompat.getColor(getContext(), R.color.cal_day));
                t.setTypeface(Typeface.DEFAULT);
            }
        }
    }

    private static boolean sameMonth(final Calendar a, final Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.MONTH) == b.get(Calendar.MONTH);
    }

    private void pick(final int index) {
        final CharSequence text = _cells[index].getText();
        if (text == null || text.length() == 0) {
            return;
        }
        _selected.set(Calendar.YEAR, _shown.get(Calendar.YEAR));
        _selected.set(Calendar.MONTH, _shown.get(Calendar.MONTH));
        _selected.set(Calendar.DAY_OF_MONTH, Integer.parseInt(text.toString()));
        bind();
        if (_dateListener != null) {
            _dateListener.onDateSelected((Calendar) _selected.clone());
        }
    }

    private void step(final int months) {
        _shown.add(Calendar.MONTH, months);
        _marked.clear();
        bind();
        if (_monthListener != null) {
            _monthListener.onMonthChanged(_shown.get(Calendar.YEAR), _shown.get(Calendar.MONTH));
        }
    }

    // ------------------------------------------------------------------- api

    public void setOnDateSelectedListener(final OnDateSelectedListener l) {
        _dateListener = l;
    }

    public void setOnMonthChangedListener(final OnMonthChangedListener l) {
        _monthListener = l;
    }

    /** The day currently selected. A copy: the grid keeps its own. */
    public Calendar getSelectedDate() {
        return (Calendar) _selected.clone();
    }

    /** Select a day, moving the view to its month if needed. */
    public void setSelectedDate(final Calendar date) {
        if (date == null) {
            return;
        }
        _selected.setTimeInMillis(date.getTimeInMillis());
        if (!sameMonth(_shown, _selected)) {
            _shown.setTimeInMillis(date.getTimeInMillis());
            _marked.clear();
            if (_monthListener != null) {
                _monthListener.onMonthChanged(_shown.get(Calendar.YEAR), _shown.get(Calendar.MONTH));
            }
        }
        bind();
    }

    /** Jump back to today and select it. */
    public void goToToday() {
        setSelectedDate(Calendar.getInstance());
        if (_dateListener != null) {
            _dateListener.onDateSelected(getSelectedDate());
        }
    }

    /** The year and month on screen, which is not always the month of the selected day. */
    public int getShownYear() {
        return _shown.get(Calendar.YEAR);
    }

    public int getShownMonth() {
        return _shown.get(Calendar.MONTH);
    }

    /**
     * Mark the days of the displayed month that have notes.
     *
     * <p>The grid does not go looking for them: it is a view, and finding out costs disk. The
     * host supplies them, and may supply nothing at all -- an unmarked calendar still works.
     */
    public void setMarkedDays(final Set<Integer> days) {
        _marked.clear();
        if (days != null) {
            _marked.addAll(days);
        }
        bind();
    }
}
