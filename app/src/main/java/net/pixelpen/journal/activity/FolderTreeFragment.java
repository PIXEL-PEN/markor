package net.pixelpen.journal.activity;

import android.os.Bundle;
import android.os.Parcelable;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.pixelpen.journal.ApplicationObject;
import net.pixelpen.journal.R;
import net.pixelpen.journal.adapter.FolderTreeAdapter;
import net.pixelpen.journal.model.ArchiveSearch;
import net.pixelpen.journal.model.CreationIndex;
import net.pixelpen.journal.util.CategoryManager;
import net.pixelpen.journal.model.TreeStore;
import net.pixelpen.journal.util.NoteTimestamp;
import net.pixelpen.journal.util.ShortcutUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The archive, as a tree of folders and notes.
 *
 * <p>This was a WebView rendering a document generated from a full walk of the archive. It looked
 * right, but it rebuilt that document every time the tab was shown, and the walk visited every
 * year and every month whether or not any of them were open. The rows here are the same design
 * drawn natively, and a directory is read only when it is about to be shown.
 *
 * <p>Which folders are open is held here rather than in the data, so it survives a rescan: notes
 * captured on the Calendar tab appear in place without the tree collapsing back to the top.
 */
public class FolderTreeFragment extends AutumnBaseFragment implements FolderTreeAdapter.Listener {

    public static final String FRAGMENT_TAG = "FolderTreeFragment";

    public static FolderTreeFragment newInstance() {
        return new FolderTreeFragment();
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.fragment_folder_tree;
    }

    @Override
    public String getFragmentTag() {
        return FRAGMENT_TAG;
    }

    private RecyclerView _list;
    private TextView _empty;
    private View _progress;

    /**
     * Resolved once, on the main thread. The rows are built on a background thread, and
     * getString() on a fragment that has since detached throws.
     */
    private String _otherFilesLabel;

    /**
     * Whether the "other files" section is open. Starts closed: the archive is what the screen is
     * for, and the material beside it grows without limit while the years do not. The arrow on the
     * heading is what says there is something under it.
     */
    private boolean _otherFilesOpen;

    private FolderTreeAdapter _adapter;
    private TreeStore _store;
    /** Held, not just handed to the store: rename, move and delete all have to update it. */
    private CreationIndex _index;

    /** Absolute paths of the folders currently open. */
    private final Set<String> _expanded = new HashSet<>();

    private File _root;

    @Override
    public void onViewCreated(@NonNull final View view, final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (android.os.Build.VERSION.SDK_INT >= 25) {
            final android.content.pm.ShortcutManager sm =
                    requireContext().getSystemService(android.content.pm.ShortcutManager.class);
            if (sm != null) {
                sm.removeAllDynamicShortcuts();
            }
        }
        try {
            ShortcutUtils.updateShortcuts(requireContext());
        } catch (final Exception ignored) {
            // Shortcuts are a convenience; the tree works without them.
        }

        _index = new CreationIndex(requireContext());
        _store = new TreeStore(_index);

        _list = view.findViewById(R.id.folder_tree_list);
        _empty = view.findViewById(R.id.folder_tree_empty);
        _progress = view.findViewById(R.id.folder_tree_progress);
        _otherFilesLabel = getString(R.string.tree_other_files);

        _empty.setText(R.string.tree_empty);
        _adapter = new FolderTreeAdapter(this);
        _list.setLayoutManager(new LinearLayoutManager(requireContext()));
        _list.setAdapter(_adapter);
        // Rows are rebuilt wholesale on expand and collapse, and the default change animation
        // makes that read as a flash rather than as a fold opening.
        _list.setItemAnimator(null);

        reload();
    }

    /**
     * Re-read the archive and redraw.
     *
     * <p>Called on first view and again whenever the Tree tab becomes visible, so a note captured
     * on the Calendar tab shows up without the fragment having to be recreated -- that loop,
     * capture then see it in place, is what this app is for.
     *
     * <p>Only folders that are currently open are read. A collapsed year costs nothing.
     */
    public void reload() {
        if (!isAdded() || _adapter == null) {
            return;
        }

        final File root = ApplicationObject.settings().getNotebookDirectory();
        if (_root == null || !_root.equals(root)) {
            // First draw, or the home folder moved underneath us. Paths from the old archive mean
            // nothing in the new one, and neither does the record of which month was opened.
            _root = root;
            _expanded.clear();
            _seededMonth = null;
            _seededCategory = null;
        }
        // Opens the year and month if they are not the ones already opened. Does nothing on a
        // plain revisit, which is what lets a collapse stick.
        seedExpansion(root);
        applyPendingReveal(root);
        _store.invalidateAll();
        redraw();
    }

    /**
     * The folder to open on. Set by the host when the Calendar screen knows where the next entry
     * would go; null falls back to the current month.
     */
    private File _focus;

    /** Point the tree at a folder, so the next draw opens the path down to it. */
    public void focusOn(final File dir) {
        _focus = dir;
    }

    /** The month folder last opened automatically, so it is only ever done once per month. */
    private String _seededMonth;

    /**
     * The category folder last opened because the dropdown pointed at it. Held so that choosing a
     * different category closes this one: without that, cycling through categories would leave
     * every one of them open, which is the behaviour this whole mechanism was cut back to avoid.
     */
    private String _seededCategory;

    /**
     * A category to open on the next draw, and only then.
     *
     * <p>Opening used to be inferred: the tree compared the Calendar's current category against
     * the one it opened last and unfolded it whenever they differed. Leaving the tree clears that
     * record, so merely coming back counted as a difference and the category sprang open again
     * every single time. Inference cannot tell "the user chose this" from "the user walked past".
     *
     * <p>So it is told instead. Choosing a category in the dropdown sets this; writing a note
     * goes through the reveal path, which opens what it reveals. Anything else -- switching tabs,
     * returning from the Year screen, rotating -- sets nothing, and the tree stays as the user
     * left it: collapsed.
     */
    private String _pendingCategoryExpand;

    /**
     * Open the year and the month, and nothing below them.
     *
     * <p>The previous version opened the path all the way down to the category and ran on every
     * reload, which was wrong twice over. Expansion state is never pruned, so every category ever
     * selected stayed open for the rest of the session and the tree ended up fully unfolded; and
     * re-seeding on each reload undid any folder the user had deliberately collapsed, because the
     * next visit to the tab put it straight back.
     *
     * <p>So: year and month only, and only when the month to open actually changes. A collapse
     * now sticks, because nothing re-adds what was removed. Picking a day in March still opens
     * March rather than today -- the Calendar screen's date decides which month this is, not the
     * clock.
     */
    private void seedExpansion(final File root) {
        _expanded.add(root.getAbsolutePath());

        final File month = targetMonthDir(root);
        if (month != null) {
            final String path = month.getAbsolutePath();
            if (!path.equals(_seededMonth)) {
                // Opened once per month. After that whatever state it is in is the user's doing.
                _seededMonth = path;
                final File year = month.getParentFile();
                if (year != null) {
                    _expanded.add(year.getAbsolutePath());
                }
                _expanded.add(path);
            }
        }

        // And the category the dropdown points at. Choosing one is a deliberate act -- it says
        // which part of the month is being worked in -- so it opens, with or without a note being
        // written. That is not the same as the earlier version, which re-opened the current
        // category on every single reload and so unfolded everything ever selected: this only
        // acts when the selection actually changes, and closes the one it opened last. At most
        // one category is ever open because of this, which is what keeps it from accumulating.
        final String wanted = _pendingCategoryExpand;
        if (wanted == null) {
            // Nothing asked for. A plain visit leaves the tree exactly as it was.
            return;
        }
        _pendingCategoryExpand = null;

        final File target = new File(wanted);
        if (!isUnder(root, target)) {
            // Left over from another archive root; it could never draw.
            return;
        }
        if (_seededCategory != null && !_seededCategory.equals(wanted)) {
            // Close the one opened by the previous request, so two cannot show at once when no
            // tab switch has intervened to collapse them.
            _expanded.remove(_seededCategory);
        }
        _seededCategory = wanted;
        for (File f = target; f != null && isUnder(root, f); f = f.getParentFile()) {
            _expanded.add(f.getAbsolutePath());
        }
    }

    /**
     * Open this category the next time the tree draws.
     *
     * <p>Called when the Calendar's dropdown changes, which is a deliberate statement about where
     * work is happening. Nothing else calls it, which is what keeps a plain return to the tree
     * from unfolding anything.
     */
    public void expandCategoryOnNextDraw(final File dir) {
        _pendingCategoryExpand = (dir == null) ? null : dir.getAbsolutePath();
    }

    /**
     * The month folder to open: the one the Calendar screen points at, else the current one.
     *
     * <p>Derived from the focus path's first two segments under the root rather than by taking
     * its parent, so it lands on the month whether the focus is a category, a month, or something
     * deeper that gets added later.
     */
    private File targetMonthDir(final File root) {
        final File focus = _focus;
        if (focus != null && isUnder(root, focus)) {
            final String rel = focus.getAbsolutePath()
                    .substring(root.getAbsolutePath().length()).replaceFirst("^/", "");
            final String[] parts = rel.split("/");
            if (parts.length >= 2) {
                return new File(new File(root, parts[0]), parts[1]);
            }
        }
        final Calendar now = Calendar.getInstance();
        return new File(new File(root, NoteTimestamp.yearFolderName(now)),
                NoteTimestamp.monthFolderName(now));
    }

    /**
     * Close every category, leaving the root, the year and the month open.
     *
     * <p>Called when the Tree tab is left. Tracking which single category was opened automatically
     * and closing it on change turned out not to be enough -- a category opened by hand, or left
     * over from revealing a captured note, is indistinguishable from one opened automatically, so
     * they piled up and two categories showed at once.
     *
     * <p>Clearing them on the way out makes it unconditional instead of bookkeeping: come back and
     * exactly one category is open, the one the dropdown points at. The year and month stay, since
     * those are the default the tree should show.
     */
    public void collapseCategories() {
        final File root = _root;
        if (root == null) {
            return;
        }
        final String rootPath = root.getAbsolutePath();
        final java.util.Iterator<String> it = _expanded.iterator();
        while (it.hasNext()) {
            final String path = it.next();
            if (path.equals(rootPath)) {
                continue;
            }
            if (!path.startsWith(rootPath + "/")) {
                // Left over from another archive root; it can never draw anyway.
                it.remove();
                continue;
            }
            final String rel = path.substring(rootPath.length() + 1);
            // One segment is a year, two is a month; anything deeper is a category or below.
            if (rel.isEmpty() || rel.split("/").length > 2) {
                it.remove();
            }
        }
        // Nothing is open now, so there is nothing left to close on the next request.
        _seededCategory = null;
    }

    /**
     * Set when the next draw should start at the top rather than hold the current position.
     * Nothing sets it at the moment: Home became a hand-off to Android's file browser rather than
     * a view in the tree, and it was the only caller. Kept because the restore-the-scroll branch
     * beside it needs a way to be told not to.
     */
    private boolean _scrollToTop;

    /** A note to make visible on the next draw. See {@link #revealPathTo}. */
    private File _pendingReveal;

    /**
     * Open the path down to one note, once.
     *
     * <p>The exception to year-and-month-only, and deliberately not part of the default: a note
     * just written should be visible when the Tree comes up, not filed away inside a category
     * folder the user then has to go and open. Called after a capture, for that note alone.
     *
     * <p>Recorded rather than applied immediately: this is called from the host the moment a note
     * is created, which can be before this fragment's view exists and before it knows the root.
     * {@link #reload} applies it.
     */
    public void revealPathTo(final File file) {
        _pendingReveal = file;
    }

    private void applyPendingReveal(final File root) {
        final File file = _pendingReveal;
        if (file == null) {
            return;
        }
        _pendingReveal = null;
        if (!isUnder(root, file)) {
            return;
        }
        for (File f = file.getParentFile(); f != null && isUnder(root, f); f = f.getParentFile()) {
            _expanded.add(f.getAbsolutePath());
        }
    }

    /** True if {@code f} is {@code root} or sits beneath it. */
    private static boolean isUnder(final File root, final File f) {
        final String r = root.getAbsolutePath();
        final String p = f.getAbsolutePath();
        return p.equals(r) || p.startsWith(r.endsWith("/") ? r : r + "/");
    }

    /**
     * The current query, or null when not searching.
     *
     * <p>This is the one switch between the two things this list shows: the tree, read a directory
     * at a time, and a flat set of search results from a walk of everything.
     */
    private String _query;

    /** Debounce for typing. See {@link #setSearch}. */
    private final android.os.Handler _searchDelay =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable _pendingSearch;

    /** How long to wait for typing to stop before walking the archive. */
    private static final long SEARCH_DEBOUNCE_MS = 250L;

    /**
     * Set or clear the query. Called by the host as the toolbar's search field changes.
     *
     * <p>Debounced, and that is not a nicety: the field reports every keystroke, and each search
     * walks the whole archive and opens the body of every note whose title did not match. Typing
     * "project" unthrottled is seven of those walks, six of which nobody will ever see the result
     * of. Waiting for a pause coalesces them into one.
     *
     * <p>Clearing it puts the tree back exactly as it was: expansion state lives in this fragment
     * rather than in the rows, so searching and coming back does not collapse anything.
     */
    public void setSearch(final String query) {
        final String trimmed = (query == null) ? null : query.trim();
        final String next = (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
        if (next == null ? _query == null : next.equals(_query)) {
            // The field also re-fires on submit, which would otherwise walk the archive again for
            // an answer already on screen.
            return;
        }
        _query = next;

        if (_pendingSearch != null) {
            _searchDelay.removeCallbacks(_pendingSearch);
            _pendingSearch = null;
        }
        if (_query == null) {
            // Going back to the tree is instant; there is nothing to wait for.
            rebuild();
            return;
        }
        _pendingSearch = this::runSearch;
        _searchDelay.postDelayed(_pendingSearch, SEARCH_DEBOUNCE_MS);
    }

    @Override
    public void onDestroyView() {
        // A pending search firing after the views are gone would be drawing into a dead adapter.
        if (_pendingSearch != null) {
            _searchDelay.removeCallbacks(_pendingSearch);
            _pendingSearch = null;
        }
        // Dropped rather than left dangling. A walk already running cannot be called back, and
        // its completion checks these before touching anything -- isAdded() alone does not cover
        // it, because a fragment whose view has been destroyed is still added.
        _list = null;
        _empty = null;
        _progress = null;
        _adapter = null;
        super.onDestroyView();
    }

    /** Draw whichever of the two views is current. */
    private void redraw() {
        if (_query == null) {
            rebuild();
        } else {
            runSearch();
        }
    }

    /**
     * Search the archive and show the matches as a flat list.
     *
     * <p>A full walk, which is the opposite of how the tree reads, and deliberately so: a note
     * matches wherever it sits. It only happens because someone typed something.
     */
    private void runSearch() {
        final File root = _root;
        final String query = _query;
        if (root == null || query == null) {
            return;
        }
        // Taken here, on the main thread, and the application context rather than the fragment's:
        // getString() off a detached fragment throws, and the walk can outlive the view.
        final android.content.Context ctx = requireContext().getApplicationContext();
        new Thread(() -> {
            final List<ArchiveSearch.Hit> hits = ArchiveSearch.run(root, query, _index);
            final List<FolderTreeAdapter.Row> rows = new ArrayList<>(hits.size());
            for (final ArchiveSearch.Hit h : hits) {
                // Just the category, not the whole relative path. The date sitting next to it on
                // the same line already gives the year and the month, so "2026/11_November/Work"
                // repeated two thirds of what was beside it.
                final int cut = h.where.lastIndexOf('/');
                final String category = (cut >= 0) ? h.where.substring(cut + 1) : h.where;
                // A body match says which line, both so the reader can see the hit was in the
                // text rather than the title and because that is where tapping will land them.
                final String where = h.inBody
                        ? category + "  ·  " + ctx.getString(R.string.tree_search_in_body, h.line + 1)
                        : category;
                rows.add(new FolderTreeAdapter.Row(
                        TreeStore.Entry.forNote(h.file, h.display, h.createdMillis),
                        0, false, where, h.line));
            }
            final boolean capped = ArchiveSearch.wasCapped(hits);

            final android.app.Activity host = getActivity();
            if (host == null) {
                return;
            }
            host.runOnUiThread(() -> {
                // The query may have moved on while the walk was running; if it has, these rows
                // are for a query nobody is asking about any more.
                if (!isAdded() || _adapter == null || _list == null || !query.equals(_query)) {
                    return;
                }
                _adapter.setRows(rows);
                _list.scrollToPosition(0);
                _progress.setVisibility(View.GONE);
                _empty.setText(getString(R.string.tree_search_no_results, query));
                _empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                if (capped) {
                    toast(R.string.tree_search_truncated);
                }
            });
        }).start();
    }

    /**
     * Rebuild the visible rows.
     *
     * <p>Reading a directory touches the filesystem, and for long-titled notes it opens each one
     * to decide a badge, so the walk runs off the main thread. Results already cached come back
     * fast enough that expanding a folder does not visibly wait.
     */
    private void rebuild() {
        final File root = _root;
        if (root == null) {
            return;
        }
        // Snapshot, so a tap landing mid-walk cannot mutate the set being read.
        final Set<String> open = new HashSet<>(_expanded);

        new Thread(() -> {
            final List<FolderTreeAdapter.Row> rows = new ArrayList<>();
            if (root.isDirectory()) {
                final boolean rootOpen = open.contains(root.getAbsolutePath());
                rows.add(new FolderTreeAdapter.Row(TreeStore.Entry.forDirectory(root), 0, rootOpen));
                if (rootOpen) {
                    collect(rows, root, 1, open);
                }
            }

            final android.app.Activity host = getActivity();
            if (host == null) {
                return;
            }
            host.runOnUiThread(() -> {
                if (!isAdded() || _adapter == null || _list == null) {
                    return;
                }
                // Hold the scroll position across the swap, otherwise every expand and every
                // return to this tab throws you back to the top of the archive.
                final RecyclerView.LayoutManager lm = _list.getLayoutManager();
                final boolean toTop = _scrollToTop;
                _scrollToTop = false;
                final Parcelable scroll = (lm == null || toTop) ? null : lm.onSaveInstanceState();

                _adapter.setRows(rows);

                if (toTop) {
                    _list.scrollToPosition(0);
                } else if (scroll != null && lm != null) {
                    lm.onRestoreInstanceState(scroll);
                }
                _progress.setVisibility(View.GONE);
                // Put the message back: a search leaves "nothing matched <query>" in here.
                _empty.setText(R.string.tree_empty);
                // Empty only counts when the root is open and still produced nothing. A closed
                // root also leaves one row, and the list must stay visible either way -- hiding
                // it would take away the only row there is to tap to open it again.
                final boolean bare = rows.size() <= 1 && open.contains(root.getAbsolutePath());
                _empty.setVisibility(bare ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    /**
     * Append the contents of one open directory, recursing into those that are themselves open.
     *
     * <p>The root is split in two. The home folder holds the journal -- a folder per year -- and
     * also everything that accumulates beside it: the timeline, images, documents, downloaded
     * pages. Listed together they were one undifferentiated column, and which was which had to be
     * inferred from the folder names. The years come first, then a rule and a heading, then the
     * rest.
     *
     * <p>A year is a four-digit folder name. That is the archive's own rule, not a guess: it is
     * what the capture screen creates and what the year overview looks for.
     */
    private void collect(final List<FolderTreeAdapter.Row> out, final File dir, final int depth,
                         final Set<String> open) {
        final TreeStore.Dir contents = _store.load(dir);

        if (_root != null && dir.equals(_root)) {
            final List<TreeStore.Entry> years = new ArrayList<>();
            final List<TreeStore.Entry> others = new ArrayList<>();
            for (final TreeStore.Entry sub : contents.dirs) {
                (sub.file.getName().matches("\\d{4}") ? years : others).add(sub);
            }
            for (final TreeStore.Entry sub : years) {
                appendDir(out, sub, depth, open);
            }
            if (!others.isEmpty() || !contents.files.isEmpty()) {
                out.add(FolderTreeAdapter.Row.heading(_otherFilesLabel, _otherFilesOpen));
                if (_otherFilesOpen) {
                    for (final TreeStore.Entry sub : others) {
                        appendDir(out, sub, depth, open);
                    }
                    for (final TreeStore.Entry f : contents.files) {
                        out.add(new FolderTreeAdapter.Row(f, depth, false));
                    }
                }
            }
            return;
        }

        for (final TreeStore.Entry sub : contents.dirs) {
            appendDir(out, sub, depth, open);
        }
        for (final TreeStore.Entry f : contents.files) {
            out.add(new FolderTreeAdapter.Row(f, depth, false));
        }
    }

    private void appendDir(final List<FolderTreeAdapter.Row> out, final TreeStore.Entry sub,
                           final int depth, final Set<String> open) {
        final boolean isOpen = open.contains(sub.file.getAbsolutePath());
        out.add(new FolderTreeAdapter.Row(sub, depth, isOpen));
        if (isOpen) {
            collect(out, sub.file, depth + 1, open);
        }
    }

    @Override
    public void onSectionToggled() {
        _otherFilesOpen = !_otherFilesOpen;
        rebuild();
    }

    @Override
    public void onFolderToggled(final FolderTreeAdapter.Row row) {
        final String path = row.entry.file.getAbsolutePath();
        if (!_expanded.remove(path)) {
            _expanded.add(path);
        }
        rebuild();
    }

    /**
     * Rename, move and delete.
     *
     * <p>These used to live in the file browser's menu and act on its selection. With the browser
     * gone they were not reachable anywhere in the app, so they are on the row itself.
     *
     * <p>Folders are deliberately not offered here. Renaming a year or a month folder would move
     * every note inside it out of the structure the stamps describe, and there is no good reason
     * to do it by hand.
     */
    @Override
    public boolean onFileLongPressed(final File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        // Labels and actions are built side by side rather than dispatched on the index. Move is
        // not offered for a file with no category slot, and with a fixed if-chain on which that
        // omission would have slid Delete up into Move's place.
        final List<CharSequence> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        labels.add(getString(R.string.rename));
        actions.add(() -> promptRename(file));

        if (CategoryManager.journalMonthDir(file) != null) {
            labels.add(getString(R.string.move_to_category));
            actions.add(() -> promptMove(file));
        }

        labels.add(getString(R.string.create_shortcut));
        actions.add(() -> createShortcut(file));

        labels.add(getString(R.string.delete));
        actions.add(() -> confirmDelete(file));

        new AlertDialog.Builder(requireContext())
                .setTitle(file.getName())
                .setItems(labels.toArray(new CharSequence[0]),
                        (d, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        return true;
    }

    /**
     * Delete a folder and everything under it.
     *
     * <p>Only delete. Renaming a folder is deliberately not offered: a year or month folder's name
     * is what files the notes inside it, and renaming one strips them out of the structure their
     * stamps describe without touching the notes themselves, which is a worse state than either
     * keeping or deleting them.
     *
     * <p>The home folder itself is refused. Emptying the archive from a long-press on its own row
     * is not something to make possible by accident.
     */
    @Override
    public boolean onFolderLongPressed(final File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        if (_root != null && dir.equals(_root)) {
            return false;
        }

        final int notes = countFilesUnder(dir);
        final String message = (notes == 0)
                ? getString(R.string.tree_delete_folder_empty)
                : getResources().getQuantityString(R.plurals.tree_delete_folder_count, notes, notes);

        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.tree_delete_folder, dir.getName()))
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    final File parent = dir.getParentFile();
                    if (deleteRecursively(dir)) {
                        _index.save();
                        _expanded.remove(dir.getAbsolutePath());
                        refreshAfterChange(parent, null);
                    } else {
                        toast(R.string.error_could_not_open_file);
                    }
                })
                .show();
        return true;
    }

    /** How many files sit under a folder, at any depth. */
    private static int countFilesUnder(final File dir) {
        final File[] kids = dir.listFiles();
        if (kids == null) {
            return 0;
        }
        int n = 0;
        for (final File k : kids) {
            n += k.isDirectory() ? countFilesUnder(k) : 1;
        }
        return n;
    }

    /**
     * Remove a folder and its contents, dropping each note from the creation index on the way.
     *
     * <p>The index is keyed by path. Left behind, those entries would hand their creation times to
     * any future note that happened to land on the same path -- which, in an archive whose folders
     * are years, months and categories, is not a far-fetched collision.
     */
    private boolean deleteRecursively(final File f) {
        final File[] kids = f.listFiles();
        if (kids != null) {
            for (final File k : kids) {
                if (!deleteRecursively(k)) {
                    return false;
                }
            }
        }
        if (f.isFile() && _index != null) {
            _index.remove(f);
        }
        return f.delete();
    }

    private void promptRename(final File file) {
        final String name = file.getName();
        final int dot = name.lastIndexOf('.');
        final String stem = (dot > 0) ? name.substring(0, dot) : name;
        final String ext = (dot > 0) ? name.substring(dot) : "";

        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(stem);
        input.setSelection(stem.length());

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.rename)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    // Same sanitising the capture screen applies, so a title typed here cannot
                    // produce a name the capture screen would never have created.
                    final String typed = input.getText().toString().trim()
                            .replaceAll("[\\\\/:*?\"<>|]", "_");
                    if (typed.isEmpty() || typed.equals(stem)) {
                        return;
                    }
                    final File dest = new File(file.getParentFile(), typed + ext);
                    if (dest.exists()) {
                        toast(R.string.file_folder_already_exists_please_use_a_different_name);
                        return;
                    }
                    if (file.renameTo(dest)) {
                        _index.moved(file, dest);
                        _index.save();
                        refreshAfterChange(file.getParentFile(), null);
                    } else {
                        toast(R.string.error_could_not_open_file);
                    }
                })
                .show();
    }

    /**
     * Pin a launcher shortcut that opens this one note.
     *
     * <p>The helper, the activity it targets, its icon and the manifest permissions all survived
     * the trim; only the call site went with the file browser's menu.
     *
     * <p>Both outcomes are reported, because neither was. requestPinShortcut returns a boolean
     * that every wrapper between here and it discards, so a launcher that does not take pinned
     * shortcuts refused in complete silence: the dialog closed and nothing happened anywhere.
     * The support check is asked first instead.
     */
    private void createShortcut(final File file) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(requireContext())) {
            toast(R.string.shortcut_not_supported);
            return;
        }
        _cu.createLauncherDesktopShortcut(requireContext(), file);
        // From Oreo the launcher puts up its own "add to home screen" prompt, which is the
        // confirmation and a better one than a toast. Below that the request is a silent
        // broadcast, so this is the only word the person gets.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            toast(R.string.shortcut_added);
        }
    }

    /**
     * Move a note to another category, keeping its year and month.
     *
     * <p>This offered every folder under the home folder, by relative path. That was a short list
     * when the archive was new and an unusable one later: the Year and Month folders of every year
     * written, plus Images, Files and Quicknotes and their own year folders, to pick a category
     * from. The realistic move is a note filed under the wrong category in the month it already
     * belongs to, so that is the only move offered, and the list is just the categories.
     */
    private void promptMove(final File file) {
        final File monthDir = CategoryManager.journalMonthDir(file);
        if (monthDir == null) {
            return;
        }
        final List<String> targets = CategoryManager.moveTargetsFor(requireContext(), file);
        if (targets.isEmpty()) {
            toast(R.string.tree_no_move_targets);
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.move_to_category)
                .setItems(targets.toArray(new CharSequence[0]), (d, which) -> {
                    // The category can exist in the archive without having a folder in this
                    // particular month.
                    final File targetDir = new File(monthDir, targets.get(which));
                    if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
                        toast(R.string.error_could_not_open_file);
                        return;
                    }
                    doMove(file, targetDir);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void doMove(final File file, final File targetDir) {
        final File dest = new File(targetDir, file.getName());
        if (dest.exists()) {
            toast(R.string.file_folder_already_exists_please_use_a_different_name);
            return;
        }
        final File source = file.getParentFile();
        if (file.renameTo(dest)) {
            _index.moved(file, dest);
            _index.save();
            refreshAfterChange(source, targetDir);
        } else {
            // renameTo fails across filesystems rather than falling back to copy-and-delete.
            // Within one archive under Documents that should not arise; if it does, it needs a
            // stream copy rather than a retry.
            toast(R.string.error_could_not_open_file);
        }
    }

    private void confirmDelete(final File file) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.delete)
                .setMessage(file.getName())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    final File parent = file.getParentFile();
                    if (file.delete()) {
                        // Drop the index entry too, or a later note given the same title in the
                        // same folder inherits this one's creation time.
                        _index.remove(file);
                        _index.save();
                        refreshAfterChange(parent, null);
                    } else {
                        toast(R.string.error_could_not_open_file);
                    }
                })
                .show();
    }

    /**
     * Forget the directories a change touched, then redraw.
     *
     * <p>Goes through redraw() rather than straight to the tree: rename, move and delete are all
     * reachable from a search result, and finishing one should leave the results up with the
     * change in them, not drop back to the tree.
     */
    private void refreshAfterChange(final File a, final File b) {
        _store.invalidate(a);
        if (b != null) {
            _store.invalidate(b);
        }
        redraw();
    }

    private void toast(final int stringRes) {
        if (isAdded()) {
            android.widget.Toast.makeText(requireContext(), stringRes,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
