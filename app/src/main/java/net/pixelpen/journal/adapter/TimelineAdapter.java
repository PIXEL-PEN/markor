package net.pixelpen.journal.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import net.pixelpen.journal.R;
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

    private void bindNotes(final NotesHolder h, final Row row) {
        final Timeline.Year y = row.year;
        final Context ctx = h.itemView.getContext();
        h.bullets.removeAllViews();

        if (y.bullets.isEmpty()) {
            // An empty year is a year laid down and not yet written about, which is most of a
            // timeline for a long time. It says so, and the card is the way in to writing it.
            final TextView none = new TextView(ctx);
            none.setText(R.string.timeline_year_blank);
            none.setTextSize(14f);
            none.setTypeface(null, android.graphics.Typeface.ITALIC);
            none.setTextColor(ContextCompat.getColor(ctx, R.color.tree_meta));
            h.bullets.addView(none);
        } else {
            for (final String b : y.bullets) {
                final TextView t = new TextView(ctx);
                t.setText("•  " + b);
                t.setTextSize(14f);
                t.setTextColor(ContextCompat.getColor(ctx, R.color.tree_title));
                final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                final int pad = Math.round(3 * ctx.getResources().getDisplayMetrics().density);
                lp.setMargins(0, pad, 0, pad);
                t.setLayoutParams(lp);
                h.bullets.addView(t);
            }
        }

        h.edit.setText(y.hasNotes() ? R.string.timeline_edit : R.string.timeline_write);
        final View.OnClickListener open = v -> {
            if (_listener != null) {
                _listener.onEditNotes(y);
            }
        };
        h.edit.setOnClickListener(open);
        h.itemView.setOnClickListener(open);
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

        NotesHolder(final View v) {
            super(v);
            bullets = v.findViewById(R.id.tl_notes_bullets);
            edit = v.findViewById(R.id.tl_notes_edit);
        }
    }
}
