package net.pixelpen.journal.activity;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.pixelpen.journal.ApplicationObject;
import net.pixelpen.journal.R;
import net.pixelpen.journal.adapter.TimelineAdapter;
import net.pixelpen.journal.model.Document;
import net.pixelpen.journal.model.Timeline;
import net.pixelpen.journal.util.NoteTimestamp;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

/**
 * The life timeline.
 *
 * <p>Decades, the years inside them, what happened in each year and the one line it is remembered
 * by. Built by hand over time rather than captured: going backwards is an evening's work now and
 * then as things are remembered, and going forwards fills in with more detail because the material
 * is still to hand.
 *
 * <p>That shapes the screen more than anything else. A year with nothing written in it is a normal
 * and expected state, not a gap to hide, so an empty year still gets a bar and still opens to
 * somewhere to write. Laying down forty years of skeleton has to be one action, not forty.
 */
public class TimelineFragment extends AutumnBaseFragment implements TimelineAdapter.Listener {

    public static final String FRAGMENT_TAG = "TimelineFragment";

    public static TimelineFragment newInstance() {
        return new TimelineFragment();
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.fragment_timeline;
    }

    @Override
    public String getFragmentTag() {
        return FRAGMENT_TAG;
    }

    private TextView _title;
    private TextView _sort;
    private TextView _empty;
    private RecyclerView _list;
    private TimelineAdapter _adapter;

    private Timeline _timeline;
    private final Set<Integer> _openDecades = new HashSet<>();
    private final Set<Integer> _openYears = new HashSet<>();
    private boolean _newestFirst;
    /** Set while every decade is deliberately shut, so a reload does not reopen them. */
    private boolean _allCollapsed;

    @Override
    public void onViewCreated(@NonNull final View view, final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        _title = view.findViewById(R.id.timeline_title);
        _sort = view.findViewById(R.id.timeline_sort);
        _empty = view.findViewById(R.id.timeline_empty);
        _list = view.findViewById(R.id.timeline_list);

        _adapter = new TimelineAdapter(this);
        _list.setLayoutManager(new LinearLayoutManager(requireContext()));
        _list.setAdapter(_adapter);
        _list.setItemAnimator(null);

        _title.setOnClickListener(v -> toggleAll());
        _sort.setOnClickListener(v -> {
            _newestFirst = !_newestFirst;
            applySortLabel();
            draw();
        });
        applySortLabel();

        reload();
    }

    /** Re-read the timeline folder and redraw. */
    public void reload() {
        if (!isAdded() || _adapter == null) {
            return;
        }
        final File home = ApplicationObject.settings().getNotebookDirectory();
        new Thread(() -> {
            final Timeline timeline = Timeline.read(Timeline.folderIn(home));
            final android.app.Activity host = getActivity();
            if (host == null) {
                return;
            }
            host.runOnUiThread(() -> {
                if (!isAdded() || _adapter == null || _list == null) {
                    return;
                }
                _timeline = timeline;
                // Open the most recent decade on a first read, so the screen does not arrive as a
                // column of shut bars. After that, what is open is the reader's business.
                if (_openDecades.isEmpty() && !_allCollapsed && !timeline.years.isEmpty()) {
                    _openDecades.add(timeline.years.get(timeline.years.size() - 1).decade());
                }
                draw();
            });
        }).start();
    }

    private void draw() {
        if (_timeline == null || _adapter == null) {
            return;
        }
        _title.setText(getString(R.string.timeline_header) + (_openDecades.isEmpty() ? "  ▶" : "  ▼"));
        _adapter.setData(_timeline.years, _openDecades, _openYears, _newestFirst);

        final boolean bare = _timeline.years.isEmpty();
        _empty.setText(R.string.timeline_empty);
        _empty.setVisibility(bare ? View.VISIBLE : View.GONE);
    }

    private void applySortLabel() {
        // Which way the list currently runs, not which way tapping would send it.
        _sort.setText(_newestFirst ? "▼" : "▲");
    }

    /** Shut every decade, or open the most recent one again. */
    private void toggleAll() {
        if (_openDecades.isEmpty()) {
            _allCollapsed = false;
            if (_timeline != null && !_timeline.years.isEmpty()) {
                _openDecades.add(_timeline.years.get(_timeline.years.size() - 1).decade());
            }
        } else {
            _openDecades.clear();
            _openYears.clear();
            _allCollapsed = true;
        }
        draw();
    }

    @Override
    public void onDecadeTapped(final int decade) {
        if (!_openDecades.remove(decade)) {
            _openDecades.add(decade);
        }
        _allCollapsed = _openDecades.isEmpty();
        draw();
    }

    @Override
    public void onYearTapped(final Timeline.Year year) {
        if (!_openYears.remove(year.year)) {
            _openYears.add(year.year);
        }
        draw();
    }

    @Override
    public boolean onYearLongPressed(final Timeline.Year year) {
        final CharSequence[] actions = {
                getString(R.string.timeline_set_highlight),
                getString(R.string.timeline_write),
                getString(R.string.delete),
        };
        new AlertDialog.Builder(requireContext())
                .setTitle(String.valueOf(year.year))
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        promptHighlight(year);
                    } else if (which == 1) {
                        onEditNotes(year);
                    } else {
                        confirmRemoveYear(year);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        return true;
    }

    @Override
    public void onHighlightTapped(final Timeline.Year year) {
        promptHighlight(year);
    }

    /**
     * The year's one line.
     *
     * <p>Edited as a line in a dialog rather than opened as a note. It is one sentence that has to
     * fit a pill, and sending someone into a full editor to change six words invites six
     * paragraphs, which the bar then cannot show.
     */
    private void promptHighlight(final Timeline.Year year) {
        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint(R.string.timeline_highlight_hint);
        if (year.highlight != null) {
            input.setText(year.highlight);
            input.setSelection(year.highlight.length());
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.timeline_highlight_for, year.year))
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    final String text = input.getText().toString().trim();
                    writeHighlight(year, text);
                })
                .show();
    }

    private void writeHighlight(final Timeline.Year year, final String text) {
        if (text.isEmpty()) {
            // Clearing it removes the file rather than leaving an empty one, so the year goes back
            // to having no pill instead of a blank one.
            if (year.highlightFile.isFile() && !year.highlightFile.delete()) {
                toast(R.string.error_could_not_open_file);
                return;
            }
            reload();
            return;
        }
        if (write(year.highlightFile, stampFor(year) + "\n\n" + text + "\n")) {
            reload();
        }
    }

    @Override
    public void onEditNotes(final Timeline.Year year) {
        final File notes = year.notesFile;
        if (!notes.isFile() && !write(notes, stampFor(year) + "\n\n- ")) {
            return;
        }
        // The editor, not preview: this screen exists to be written into.
        final Intent i = new Intent(requireContext(), DocumentActivity.class);
        i.putExtra(Document.EXTRA_FILE, notes);
        startActivity(i);
    }

    /**
     * Add years.
     *
     * <p>A range, not one at a time. A timeline usually starts at a birth year and runs to now, so
     * the first thing anyone does is lay down sixty or eighty years at once; asking for them
     * individually would make the feature unusable on its first use.
     *
     * <p>Only folders are created. The note inside a year is written the first time there is
     * something to put in it, so laying down a skeleton does not scatter eighty stub files that
     * each say nothing.
     */
    public void promptAddYears() {
        final View box = android.view.LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_timeline_years, null);
        final EditText from = box.findViewById(R.id.timeline_from);
        final EditText to = box.findViewById(R.id.timeline_to);
        to.setHint(String.valueOf(Calendar.getInstance().get(Calendar.YEAR)));

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.timeline_add_years)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    final String a = from.getText().toString().trim();
                    final String b = to.getText().toString().trim();
                    if (!Timeline.isYear(a)) {
                        toast(R.string.timeline_bad_year);
                        return;
                    }
                    final int start = Integer.parseInt(a);
                    final int end = Timeline.isYear(b) ? Integer.parseInt(b)
                            : (b.isEmpty() ? Calendar.getInstance().get(Calendar.YEAR) : -1);
                    if (end < 0) {
                        toast(R.string.timeline_bad_year);
                        return;
                    }
                    addYears(Math.min(start, end), Math.max(start, end));
                })
                .show();
    }

    private void addYears(final int from, final int to) {
        final File home = ApplicationObject.settings().getNotebookDirectory();
        final File dir = Timeline.folderIn(home);
        if (dir == null) {
            return;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            toast(R.string.error_could_not_open_file);
            return;
        }
        int made = 0;
        for (int y = from; y <= to; y++) {
            final File yearDir = new File(dir, String.valueOf(y));
            if (!yearDir.isDirectory() && yearDir.mkdirs()) {
                made++;
            }
        }
        // Opening the decade the new years landed in, so adding a range visibly does something
        // rather than appearing to do nothing behind a shut bar.
        _openDecades.add((to / 10) * 10);
        _allCollapsed = false;
        toast(made > 0 ? R.string.timeline_years_added : R.string.timeline_years_existed);
        reload();
    }

    private void confirmRemoveYear(final Timeline.Year year) {
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.timeline_remove_year, year.year))
                .setMessage(year.hasNotes() || year.highlight != null
                        ? R.string.timeline_remove_warning : R.string.timeline_remove_plain)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    if (deleteTree(year.dir)) {
                        _openYears.remove(year.year);
                        reload();
                    } else {
                        toast(R.string.error_could_not_open_file);
                    }
                })
                .show();
    }

    /** Remove a year folder and everything filed under it. */
    private static boolean deleteTree(final File f) {
        if (f == null) {
            return false;
        }
        final File[] kids = f.listFiles();
        if (kids != null) {
            for (final File k : kids) {
                if (!deleteTree(k)) {
                    return false;
                }
            }
        }
        return f.delete();
    }

    /**
     * A stamp for a timeline entry: the year, and only the year.
     *
     * <p>The year it is about, not the evening it was typed -- a note about 1973 written tonight
     * is about 1973, and the archive reads line one as the date a thing is about.
     *
     * <p>It used to write January of that year, because line one has to be recognisable as a
     * stamp or it is rendered as the first bullet, and a month stamp was the coarsest form there
     * was. That meant asserting a month nobody had chosen, on an entry that covers the whole
     * year, for a purely mechanical reason. A year-only stamp claims exactly what is known.
     */
    private static String stampFor(final Timeline.Year year) {
        final Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year.year, Calendar.JANUARY, 1);
        return NoteTimestamp.formatYear(c);
    }

    private boolean write(final File file, final String content) {
        try {
            final File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                toast(R.string.error_could_not_open_file);
                return false;
            }
            final FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(content.getBytes("UTF-8"));
            } finally {
                out.close();
            }
            return true;
        } catch (final Exception e) {
            toast(R.string.error_could_not_open_file);
            return false;
        }
    }

    private void toast(final int stringRes) {
        if (isAdded()) {
            Toast.makeText(requireContext(), stringRes, Toast.LENGTH_SHORT).show();
        }
    }

    /** Back closes an open year before it leaves the app. */
    @Override
    public boolean onBackPressed() {
        if (!_openYears.isEmpty()) {
            _openYears.clear();
            draw();
            return true;
        }
        return false;
    }

    @Override
    public void onDestroyView() {
        _adapter = null;
        _list = null;
        super.onDestroyView();
    }
}
