package net.pixelpen.journal.adapter;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import net.pixelpen.journal.R;
import net.pixelpen.journal.model.YearOverview;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The month boxes of the year overview.
 *
 * <p>Each box is a title and the month's events for the selected category, as a bulleted list.
 * The list is built as plain TextViews rather than a nested RecyclerView: a month holds a handful
 * of lines, and a recycler inside a recycler costs more in complexity than it saves in views.
 */
public class YearMonthAdapter extends RecyclerView.Adapter<YearMonthAdapter.MonthHolder> {

    /** What the screen wants to know about. */
    public interface Listener {
        /** A month's title was tapped: isolate it, or bring the rest back. */
        void onMonthTapped(int index);

        /** An event was tapped. */
        void onEventTapped(File file);
    }

    private final List<YearOverview.Month> _months = new ArrayList<>();
    private final Listener _listener;
    private String _category = YearOverview.DEFAULT_CATEGORY;

    /**
     * The month shown alone, or -1 for all of them.
     *
     * <p>Isolating is done by filtering the list rather than by hiding rows, so the recycler is
     * never asked to lay out eleven zero-height boxes.
     */
    private int _isolated = -1;

    /** Indices into the full twelve, in the order currently displayed. */
    private final List<Integer> _visible = new ArrayList<>();

    public YearMonthAdapter(final Listener listener) {
        _listener = listener;
    }

    public void setMonths(final List<YearOverview.Month> months) {
        _months.clear();
        if (months != null) {
            _months.addAll(months);
        }
        rebuildVisible();
    }

    public void setCategory(final String category) {
        _category = category;
        notifyDataSetChanged();
    }

    /** @param index a month index to show alone, or -1 for all. */
    public void setIsolated(final int index) {
        _isolated = index;
        rebuildVisible();
    }

    public int getIsolated() {
        return _isolated;
    }

    private void rebuildVisible() {
        _visible.clear();
        if (_isolated >= 0 && _isolated < _months.size()) {
            _visible.add(_isolated);
        } else {
            for (int i = 0; i < _months.size(); i++) {
                _visible.add(i);
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return _visible.size();
    }

    @NonNull
    @Override
    public MonthHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        return new MonthHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_year_month, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull final MonthHolder h, final int position) {
        final int index = _visible.get(position);
        final YearOverview.Month month = _months.get(index);
        final Context ctx = h.itemView.getContext();

        h.title.setText(month.display);
        h.title.setOnClickListener(v -> {
            if (_listener != null) {
                _listener.onMonthTapped(index);
            }
        });

        h.events.removeAllViews();
        final List<YearOverview.Event> events = month.eventsIn(_category);

        if (events.isEmpty()) {
            // The original said so rather than leaving an empty box, which otherwise reads as a
            // month that failed to load rather than one with nothing filed under this category.
            final TextView none = line(ctx);
            none.setText(ctx.getString(R.string.year_no_events));
            none.setTypeface(null, Typeface.ITALIC);
            none.setTextColor(ContextCompat.getColor(ctx, R.color.tree_meta));
            h.events.addView(none);
            return;
        }

        for (final YearOverview.Event e : events) {
            final TextView row = line(ctx);
            row.setText("•  " + e.title);
            row.setTextColor(ContextCompat.getColor(ctx, R.color.tree_title));
            row.setOnClickListener(v -> {
                if (_listener != null) {
                    _listener.onEventTapped(e.file);
                }
            });
            h.events.addView(row);
        }
    }

    private static TextView line(final Context ctx) {
        final TextView t = new TextView(ctx);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        final int pad = Math.round(4 * ctx.getResources().getDisplayMetrics().density);
        lp.setMargins(0, pad, 0, pad);
        t.setLayoutParams(lp);
        t.setTextSize(14f);
        return t;
    }

    static final class MonthHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final LinearLayout events;

        MonthHolder(final View v) {
            super(v);
            title = v.findViewById(R.id.month_title);
            events = v.findViewById(R.id.month_events);
        }
    }
}
