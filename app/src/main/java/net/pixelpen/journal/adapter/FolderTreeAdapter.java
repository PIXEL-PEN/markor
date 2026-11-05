package net.pixelpen.journal.adapter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import net.pixelpen.journal.BuildConfig;
import net.pixelpen.journal.R;
import net.pixelpen.journal.activity.DocumentActivity;
import net.pixelpen.journal.model.Document;
import net.pixelpen.journal.model.TreeStore;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The folder tree, drawn as rows.
 *
 * <p>This is the native replacement for the HTML the tree used to generate, and it is meant to
 * look the same: the accent arrow that turns as a folder opens, year and month and category
 * stepping down in size, notes with a document glyph, a badge on the long-titled ones and a
 * small date line underneath.
 *
 * <p>The list is flat. {@link #setRows} is handed the rows that are currently visible, which the
 * fragment rebuilds whenever something is expanded or collapsed; folders whose contents have not
 * been read yet simply have no children in that list until the read finishes.
 */
public class FolderTreeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_FOLDER = 0;
    private static final int TYPE_FILE = 1;
    private static final int TYPE_HEADER = 2;

    /** Left step per level, matching the stylesheet's 20px indent. */
    private static final int INDENT_DP = 20;

    /** The page's own left margin, matching the stylesheet's 16px body padding. */
    private static final int GUTTER_DP = 16;

    /** One visible line of the tree. */
    public static final class Row {
        public final TreeStore.Entry entry;
        public final int depth;
        /** Folders only: whether this folder is currently open. */
        public boolean expanded;
        /**
         * Extra context appended to a note's date line. Null in the tree, where the row's position
         * already says where it sits; set for a search result, where it does not.
         */
        public final String subtitle;
        /**
         * Zero-based line to open the note at, or -1 for the top. Set on a search result whose
         * match was in the body.
         */
        public final int line;
        /** Section heading text; when set this row is a heading and carries no entry. */
        public final String header;

        /** A section heading, with a rule above it, that opens and closes what follows. */
        public static Row heading(final String text, final boolean open) {
            return new Row(text, open);
        }

        private Row(final String header, final boolean open) {
            this.entry = null;
            this.depth = 0;
            this.expanded = open;
            this.subtitle = null;
            this.line = -1;
            this.header = header;
        }

        public Row(final TreeStore.Entry entry, final int depth, final boolean expanded) {
            this(entry, depth, expanded, null, -1);
        }

        public Row(final TreeStore.Entry entry, final int depth, final boolean expanded,
                   final String subtitle, final int line) {
            this.entry = entry;
            this.depth = depth;
            this.expanded = expanded;
            this.subtitle = subtitle;
            this.line = line;
            this.header = null;
        }
    }

    /** What the fragment wants to know about. */
    public interface Listener {
        /** A folder row was tapped; the fragment opens or closes it and rebuilds the rows. */
        void onFolderToggled(Row row);

        /** A section heading was tapped: open or close everything under it. */
        void onSectionToggled();

        /** A note row was long-pressed. */
        boolean onFileLongPressed(File file);

        /** A folder row was long-pressed. */
        boolean onFolderLongPressed(File dir);
    }

    private final List<Row> _rows = new ArrayList<>();
    private final Listener _listener;

    /**
     * Presentation, so the device's own locale decides how a weekday is spelled. Stored dates go
     * through NoteTimestamp with a pinned locale instead; the two must not be confused.
     */
    private final SimpleDateFormat _dateFormat =
            new SimpleDateFormat("EEE | d MMM | yy", Locale.getDefault());

    public FolderTreeAdapter(final Listener listener) {
        _listener = listener;
        setHasStableIds(false);
    }

    public void setRows(final List<Row> rows) {
        _rows.clear();
        if (rows != null) {
            _rows.addAll(rows);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return _rows.size();
    }

    @Override
    public int getItemViewType(final int position) {
        final Row row = _rows.get(position);
        if (row.header != null) {
            return TYPE_HEADER;
        }
        return row.entry.isDir ? TYPE_FOLDER : TYPE_FILE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderHolder(inflater.inflate(R.layout.item_tree_header, parent, false));
        }
        if (viewType == TYPE_FOLDER) {
            return new FolderHolder(inflater.inflate(R.layout.item_tree_folder, parent, false));
        }
        return new FileHolder(inflater.inflate(R.layout.item_tree_file, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder, final int position) {
        final Row row = _rows.get(position);
        if (holder instanceof HeaderHolder) {
            final HeaderHolder hh = (HeaderHolder) holder;
            hh.label.setText(row.header + (row.expanded ? "  \u25BC" : "  \u25B6"));
            hh.itemView.setOnClickListener(v -> {
                if (_listener != null) {
                    _listener.onSectionToggled();
                }
            });
            return;
        }
        if (holder instanceof FolderHolder) {
            bindFolder((FolderHolder) holder, row);
        } else {
            bindFile((FileHolder) holder, row);
        }
    }

    private void bindFolder(final FolderHolder h, final Row row) {
        h.label.setText(row.entry.display);

        // Year, month and category each read a step smaller than the one above, as they did in
        // the stylesheet. Anything deeper than a category is unstyled body text.
        final float size;
        final boolean bold;
        switch (row.depth) {
            case 0:
                size = 17f;
                bold = false;
                break;
            case 1:
                size = 19f;
                bold = true;
                break;
            case 2:
                size = 18f;
                bold = true;
                break;
            case 3:
                size = 16f;
                bold = true;
                break;
            default:
                size = 16f;
                bold = false;
                break;
        }
        h.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        h.label.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);

        // Rotated, not swapped for a second glyph, so it turns the way the CSS transition did.
        h.arrow.setRotation(row.expanded ? 90f : 0f);

        indent(h.itemView, row.depth);

        h.itemView.setOnClickListener(v -> {
            if (_listener != null) {
                _listener.onFolderToggled(row);
            }
        });
        h.itemView.setOnLongClickListener(v ->
                _listener != null && _listener.onFolderLongPressed(row.entry.file));
    }

    private void bindFile(final FileHolder h, final Row row) {
        final TreeStore.Entry e = row.entry;
        h.title.setText(e.display);
        final String date = _dateFormat.format(new Date(e.createdMillis));
        // A search result has to say where it lives; a row in the tree is already standing there.
        h.meta.setText((row.subtitle == null || row.subtitle.isEmpty())
                ? date
                : date + "  ·  " + row.subtitle);

        // The badge marks a note whose title is the whole entry: filled once there is something
        // written past the date stamp, hollow while there is not.
        if (e.longTitle) {
            h.badge.setVisibility(View.VISIBLE);
            h.badge.setBackgroundResource(
                    e.substantial ? R.drawable.tree_dot_full : R.drawable.tree_dot_empty);
        } else {
            h.badge.setVisibility(View.GONE);
        }

        indent(h.itemView, row.depth);

        h.itemView.setOnClickListener(v -> open(v.getContext(), e.file, row.line));
        h.itemView.setOnLongClickListener(v ->
                _listener != null && _listener.onFileLongPressed(e.file));
    }

    private static void indent(final View view, final int depth) {
        final float density = view.getResources().getDisplayMetrics().density;
        final int start = Math.round((GUTTER_DP + (depth * INDENT_DP)) * density);
        view.setPaddingRelative(start, view.getPaddingTop(), view.getPaddingEnd(), view.getPaddingBottom());
    }

    /**
     * Open a note.
     *
     * <p>Markdown and text go to this app's editor. HTML goes out as a view intent, pinned to
     * this package so it opens here rather than in a browser -- or, on a device that still has
     * the app this one was forked from, in that.
     *
     * <p>{@code line} is where to put the cursor, or -1 for the top. A search result that matched
     * in the body passes the line it matched on; DocumentActivity opens in edit mode rather than
     * preview whenever a line is given, which is what makes the match visible.
     */
    private static void open(final Context context, final File file, final int line) {
        File target = file;
        if (target == null || !target.isFile()) {
            target = findByPrefix(file);
        }
        if (target == null || !target.isFile()) {
            Toast.makeText(context, R.string.error_could_not_open_file, Toast.LENGTH_SHORT).show();
            return;
        }

        final String name = target.getName().toLowerCase(Locale.ROOT);
        try {
            if (!isEditable(name) && !name.endsWith(".html") && !name.endsWith(".htm")) {
                // Not a note and not a page: hand it to whatever on the device owns it, rather
                // than opening a PDF or a zip in a markdown editor. The home folder holds these
                // now, so they have to do something sensible when tapped.
                final android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        context, BuildConfig.APPLICATION_ID + ".provider", target);
                final Intent i = new Intent(Intent.ACTION_VIEW);
                final String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
                final String mime = android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(ext);
                i.setDataAndType(uri, (mime != null) ? mime : "*/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                context.startActivity(Intent.createChooser(i, target.getName()));
            } else if (name.endsWith(".html") || name.endsWith(".htm")) {
                final android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        context, BuildConfig.APPLICATION_ID + ".provider", target);
                final Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "text/html");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                i.setPackage(context.getPackageName());
                context.startActivity(i);
            } else if (context instanceof Activity) {
                // The app's own entry point rather than a hand-built intent. That distinction is
                // what broke jumping to a line the first time: a raw intent skips the format
                // check, the multi-window and single-top flags, and -- the part that actually
                // matters -- the line never reached the editor. The fragment reads the line from
                // its own arguments in onFragmentFirstTimeVisible, and only launch() puts it
                // there, by way of newInstance(). Sent as an activity extra it was simply
                // dropped on any path where DocumentActivity was reused.
                //
                // Preview is off when a line is given, since a line is a position in the text and
                // there is nothing to position in a rendered preview.
                DocumentActivity.launch((Activity) context, target,
                        (line >= 0) ? Boolean.FALSE : Boolean.TRUE,
                        (line >= 0) ? Integer.valueOf(line) : null);
            } else {
                // No activity to launch from, which should not happen for a tapped row. Open it
                // plainly rather than doing nothing; the line is lost, the note is not.
                final Intent i = new Intent(context, DocumentActivity.class);
                i.putExtra(Document.EXTRA_FILE, target);
                i.putExtra(Document.EXTRA_DO_PREVIEW, true);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
            }
        } catch (final Exception e) {
            Toast.makeText(context, R.string.error_could_not_open_file, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Last resort when a path does not resolve.
     *
     * <p>Titles in this app can be long enough that the filesystem truncates the name it actually
     * stored, so the name in hand and the name on disk disagree past some cutoff. Matching on the
     * longest shared prefix finds the file anyway.
     */
    private static File findByPrefix(final File expected) {
        if (expected == null) {
            return null;
        }
        final File dir = expected.getParentFile();
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        String stem = expected.getName();
        final int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            stem = stem.substring(0, dot);
        }
        final String probe = stem.substring(0, Math.min(200, stem.length()));
        if (probe.isEmpty()) {
            return null;
        }

        final File[] kids = dir.listFiles();
        if (kids == null) {
            return null;
        }
        File best = null;
        for (final File k : kids) {
            if (!k.isFile()) {
                continue;
            }
            String candidate = k.getName();
            final int d = candidate.lastIndexOf('.');
            if (d > 0) {
                candidate = candidate.substring(0, d);
            }
            if (candidate.startsWith(probe)
                    && (best == null || k.getName().length() > best.getName().length())) {
                best = k;
            }
        }
        return best;
    }

    /** Text this app's own editor should handle. */
    private static boolean isEditable(final String lowerName) {
        return lowerName.endsWith(".md") || lowerName.endsWith(".markdown")
                || lowerName.endsWith(".txt");
    }

    static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView label;

        HeaderHolder(final View v) {
            super(v);
            label = v.findViewById(R.id.tree_header_label);
        }
    }

    static final class FolderHolder extends RecyclerView.ViewHolder {
        final TextView arrow;
        final TextView label;

        FolderHolder(final View v) {
            super(v);
            arrow = v.findViewById(R.id.tree_arrow);
            label = v.findViewById(R.id.tree_label);
        }
    }

    static final class FileHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView meta;
        final View badge;

        FileHolder(final View v) {
            super(v);
            title = v.findViewById(R.id.tree_file_title);
            meta = v.findViewById(R.id.tree_file_meta);
            badge = v.findViewById(R.id.tree_file_badge);
        }
    }
}
