package net.pixelpen.journal.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import net.pixelpen.journal.R;
import net.pixelpen.journal.model.ThumbLoader;
import net.pixelpen.journal.model.Timeline;

import java.util.ArrayList;
import java.util.List;

/**
 * The timeline's rows: decade bars, year bars, and the bullet card under an open year.
 *
 * <p>One flat list of three row types rather than nested lists. A decade holding years holding
 * bullets is two levels of nesting, and a recycler inside a recycler inside a recycler would cost
 * far more than a list that is rebuilt whenever something opens or closes.
 */
public class TimelineAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_DECADE = 0;
    private static final int TYPE_YEAR = 1;
    private static final int TYPE_NOTES = 2;

    /** What the screen wants to know about. */
    public interface Listener {
        void onDecadeTapped(int decade);

        void onYearTapped(Timeline.Year year);

        boolean onYearLongPressed(Timeline.Year year);

        /** The pill was tapped: open the highlight itself. */
        void onHighlightTapped(Timeline.Year year);

        /** "Edit" on the bullet card, or the card of a year with nothing written yet. */
        void onEditNotes(Timeline.Year year);

        /** A thumbnail was tapped: open that year's pictures full screen, starting at this one. */
        void onImageTapped(Timeline.Year year, int index);
    }

    /** One visible line. */
    public static final class Row {
        final int type;
        final int decade;
        final Timeline.Year year;
        final boolean open;

        Row(final int type, final int decade, final Timeline.Year year, final boolean open) {
            this.type = type;
            this.decade = decade;
            this.year = year;
            this.open = open;
        }

        static Row decade(final int decade, final boolean open) {
            return new Row(TYPE_DECADE, decade, null, open);
        }

        static Row year(final Timeline.Year y, final boolean open) {
            return new Row(TYPE_YEAR, y.decade(), y, open);
        }

        static Row notes(final Timeline.Year y) {
            return new Row(TYPE_NOTES, y.decade(), y, true);
        }
    }

    private final List<Row> _rows = new ArrayList<>();
    private final Listener _listener;

    public TimelineAdapter(final Listener listener) {
        _listener = listener;
    }

    /**
     * Rebuild the rows from the timeline and what is currently open.
     *
     * @param newestFirst years and decades in descending order.
     */
    public void setData(final List<Timeline.Year> years, final java.util.Set<Integer> openDecades,
                        final java.util.Set<Integer> openYears, final boolean newestFirst) {
        _rows.clear();

        final List<Integer> decades = new ArrayList<>();
        for (final Timeline.Year y : years) {
            if (!decades.contains(y.decade())) {
                decades.add(y.decade());
            }
        }
        java.util.Collections.sort(decades);
        if (newestFirst) {
            java.util.Collections.reverse(decades);
        }

        for (final int decade : decades) {
            final boolean decadeOpen = openDecades.contains(decade);
            _rows.add(Row.decade(decade, decadeOpen));
            if (!decadeOpen) {
                continue;
            }
            final List<Timeline.Year> inDecade = new ArrayList<>();
            for (final Timeline.Year y : years) {
                if (y.decade() == decade) {
                    inDecade.add(y);
                }
            }
            java.util.Collections.sort(inDecade, (a, b) -> newestFirst
                    ? Integer.compare(b.year, a.year) : Integer.compare(a.year, b.year));
            for (final Timeline.Year y : inDecade) {
                final boolean yearOpen = openYears.contains(y.year);
                _rows.add(Row.year(y, yearOpen));
                if (yearOpen) {
                    _rows.add(Row.notes(y));
                }
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return _rows.size();
    }

    @Override
    public int getItemViewType(final int position) {
        return _rows.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int type) {
        final LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (type == TYPE_DECADE) {
            return new DecadeHolder(inf.inflate(R.layout.item_timeline_decade, parent, false));
        }
        if (type == TYPE_YEAR) {
            return new YearHolder(inf.inflate(R.layout.item_timeline_year, parent, false));
        }
        return new NotesHolder(inf.inflate(R.layout.item_timeline_notes, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder, final int position) {
        final Row row = _rows.get(position);
        if (holder instanceof DecadeHolder) {
            final DecadeHolder h = (DecadeHolder) holder;
            h.label.setText(Timeline.decadeLabel(row.decade) + (row.open ? "  ▼" : "  ▶"));
            h.itemView.setOnClickListener(v -> {
                if (_listener != null) {
                    _listener.onDecadeTapped(row.decade);
                }
            });
            return;
        }
        if (holder instanceof YearHolder) {
            bindYear((YearHolder) holder, row);
            return;
        }
        bindNotes((NotesHolder) holder, row);
    }

    private void bindYear(final YearHolder h, final Row row) {
        final Timeline.Year y = row.year;
        h.label.setText(String.valueOf(y.year));

        // The pill is always there, filled or not. Hidden when empty, the only way to give a year
        // its defining event was a long-press on the bar, which is not something anyone finds. A
        // dashed pill says both that the year has no highlight and where to put one.
        h.pill.setVisibility(View.VISIBLE);
        final boolean has = y.highlight != null && !y.highlight.isEmpty();
        if (has) {
            h.pill.setText(y.highlight);
            h.pill.setBackgroundResource(R.drawable.tl_pill);
            h.pill.setTextColor(0xFF3A2F00);
            h.pill.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            h.pill.setText(R.string.timeline_add_highlight);
            h.pill.setBackgroundResource(R.drawable.tl_pill_empty);
            h.pill.setTextColor(0xFF4A5A5D);
            h.pill.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
        h.pill.setOnClickListener(v -> {
            if (_listener != null) {
                _listener.onHighlightTapped(y);
            }
        });

        h.itemView.setOnClickListener(v -> {
            if (_listener != null) {
                _listener.onYearTapped(y);
            }
        });
        h.itemView.setOnLongClickListener(v ->
                _listener != null && _listener.onYearLongPressed(y));
    }

    /**
     * A year's card: months down the left, what happened beside them.
     *
     * <p>Only months with something in them. The scaffold of all twelve belongs in the note, not
     * here: most years have a handful of entries, and ten empty rows around them is not structure,
     * it is padding.
     *
     * <p>Lines that name no month go underneath, undated, rather than being guessed into one.
     */
    private void bindNotes(final NotesHolder h, final Row row) {
        final Timeline.Year y = row.year;
        final Context ctx = h.itemView.getContext();
        final float density = ctx.getResources().getDisplayMetrics().density;
        h.bullets.removeAllViews();

        final List<List<Timeline.Bullet>> byMonth = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            byMonth.add(new ArrayList<>());
        }
        final List<Timeline.Bullet> undated = new ArrayList<>();
        for (final Timeline.Bullet b : y.bullets) {
            if (b.month >= 0 && b.month < 12) {
                byMonth.get(b.month).add(b);
            } else {
                undated.add(b);
            }
        }

        // Only the months that have something in them. The seeded column of all twelve lives in
        // the note, which is where there is room for it and where it is useful as a scaffold to
        // type into; here it would be ten empty rows for a year with two entries.
        for (int m = 0; m < 12; m++) {
            if (!byMonth.get(m).isEmpty()) {
                h.bullets.addView(monthRow(ctx, density, Timeline.MONTH_SHORT[m], byMonth.get(m)));
            }
        }
        if (!undated.isEmpty()) {
            h.bullets.addView(monthRow(ctx, density, "", undated));
        }

        bindShots(h, y, density);

        h.edit.setText(y.hasNotes() ? R.string.timeline_edit : R.string.timeline_write);
        final View.OnClickListener open = v -> {
            if (_listener != null) {
                _listener.onEditNotes(y);
            }
        };
        h.edit.setOnClickListener(open);
        h.itemView.setOnClickListener(open);
    }

    /**
     * The year's pictures, as a scrolling strip of square thumbnails.
     *
     * <p>Views are rebuilt per bind rather than pooled. A year has a handful of pictures, not
     * hundreds, and the decoded bitmaps -- the only expensive part -- are cached in ThumbLoader
     * and so survive the rebuild.
     */
    private void bindShots(final NotesHolder h, final Timeline.Year y, final float density) {
        h.shots.removeAllViews();
        if (y.images.isEmpty()) {
            h.shotsScroll.setVisibility(View.GONE);
            return;
        }
        h.shotsScroll.setVisibility(View.VISIBLE);

        final Context ctx = h.itemView.getContext();
        final int side = Math.round(72 * density);
        final int gap = Math.round(6 * density);

        for (int i = 0; i < y.images.size(); i++) {
            final int index = i;
            final ImageView iv = new ImageView(ctx);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
            lp.setMarginEnd(gap);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundResource(R.drawable.tl_notes_card);
            iv.setContentDescription(y.images.get(index).getName());
            iv.setOnClickListener(v -> {
                if (_listener != null) {
                    _listener.onImageTapped(y, index);
                }
            });
            // Decoded a little larger than drawn, so it stays sharp on a dense screen.
            ThumbLoader.load(iv, y.images.get(index), Math.max(side, 220));
            h.shots.addView(iv);
        }
    }

    /** One month of the gutter and whatever sits beside it. */
    private static View monthRow(final Context ctx, final float density,
                                 final String label, final List<Timeline.Bullet> items) {
        final LinearLayout rowView = new LinearLayout(ctx);
        rowView.setOrientation(LinearLayout.HORIZONTAL);
        final LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // The spacing is the point as much as the labels: a cramped column reads as a list with a
        // prefix, and a spaced one reads as a year with room in it.
        final int gap = Math.round(7 * density);
        rowLp.setMargins(0, gap, 0, gap);
        rowView.setLayoutParams(rowLp);
        rowView.setMinimumHeight(Math.round(22 * density));

        final TextView gutter = new TextView(ctx);
        gutter.setText(label);
        gutter.setTextSize(12f);
        gutter.setTextColor(ContextCompat.getColor(ctx, R.color.tl_gutter));
        gutter.setWidth(Math.round(42 * density));
        rowView.addView(gutter);

        final LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        for (final Timeline.Bullet b : items) {
            final TextView t = new TextView(ctx);
            t.setText("\u2022  " + b.text);
            t.setTextSize(14f);
            t.setTextColor(ContextCompat.getColor(ctx, R.color.tree_title));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            final int pad = Math.round(3 * density);
            lp.setMargins(0, pad, 0, pad);
            t.setLayoutParams(lp);
            col.addView(t);
        }
        rowView.addView(col);
        return rowView;
    }

    static final class DecadeHolder extends RecyclerView.ViewHolder {
        final TextView label;

        DecadeHolder(final View v) {
            super(v);
            label = v.findViewById(R.id.tl_decade_label);
        }
    }

    static final class YearHolder extends RecyclerView.ViewHolder {
        final TextView label;
        final TextView pill;

        YearHolder(final View v) {
            super(v);
            label = v.findViewById(R.id.tl_year_label);
            pill = v.findViewById(R.id.tl_year_pill);
        }
    }

    static final class NotesHolder extends RecyclerView.ViewHolder {
        final LinearLayout bullets;
        final TextView edit;
        final View shotsScroll;
        final LinearLayout shots;

        NotesHolder(final View v) {
            super(v);
            bullets = v.findViewById(R.id.tl_notes_bullets);
            edit = v.findViewById(R.id.tl_notes_edit);
            shotsScroll = v.findViewById(R.id.tl_notes_shots_scroll);
            shots = v.findViewById(R.id.tl_notes_shots);
        }
    }
}
