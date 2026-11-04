package net.pixelpen.journal.activity;

import android.os.Bundle;
import android.os.Parcelable;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.pixelpen.journal.ApplicationObject;
import net.pixelpen.journal.R;
import net.pixelpen.journal.adapter.FolderTreeAdapter;
import net.pixelpen.journal.model.CreationIndex;
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
            // First draw, or the home folder moved underneath us. Open the path to this month so
            // the tab lands on what was most likely just written.
            _root = root;
            _expanded.clear();
            seedExpansion(root);
        }
        _store.invalidateAll();
        rebuild();
    }

    /** Open the root, the current year and the current month. */
    private void seedExpansion(final File root) {
        final Calendar now = Calendar.getInstance();
        _expanded.add(root.getAbsolutePath());
        final File year = new File(root, NoteTimestamp.yearFolderName(now));
        _expanded.add(year.getAbsolutePath());
        _expanded.add(new File(year, NoteTimestamp.monthFolderName(now)).getAbsolutePath());
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
                final Parcelable scroll = (lm == null) ? null : lm.onSaveInstanceState();

                _adapter.setRows(rows);

                if (scroll != null && lm != null) {
                    lm.onRestoreInstanceState(scroll);
                }
                _progress.setVisibility(View.GONE);
                // Empty only counts when the root is open and still produced nothing. A closed
                // root also leaves one row, and the list must stay visible either way -- hiding
                // it would take away the only row there is to tap to open it again.
                final boolean bare = rows.size() <= 1 && open.contains(root.getAbsolutePath());
                _empty.setVisibility(bare ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    /** Append the contents of one open directory, recursing into those that are themselves open. */
    private void collect(final List<FolderTreeAdapter.Row> out, final File dir, final int depth,
                         final Set<String> open) {
        final TreeStore.Dir contents = _store.load(dir);
        for (final TreeStore.Entry sub : contents.dirs) {
            final boolean isOpen = open.contains(sub.file.getAbsolutePath());
            out.add(new FolderTreeAdapter.Row(sub, depth, isOpen));
            if (isOpen) {
                collect(out, sub.file, depth + 1, open);
            }
        }
        for (final TreeStore.Entry f : contents.files) {
            out.add(new FolderTreeAdapter.Row(f, depth, false));
        }
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
        final CharSequence[] actions = {
                getString(R.string.rename),
                getString(R.string.move),
                getString(R.string.delete),
        };
        new AlertDialog.Builder(requireContext())
                .setTitle(file.getName())
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        promptRename(file);
                    } else if (which == 1) {
                        promptMove(file);
                    } else {
                        confirmDelete(file);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        return true;
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
     * Move a note to another folder in the archive.
     *
     * <p>The destination list is every folder under the home folder, shown by its path relative
     * to it. A flat list rather than a folder picker: the realistic move here is a mis-filed
     * category or a note put under the wrong month, and both are easier to spot in one list than
     * by walking down into them.
     */
    private void promptMove(final File file) {
        final File root = _root;
        if (root == null) {
            return;
        }
        final File source = file.getParentFile();

        new Thread(() -> {
            final List<File> dirs = new ArrayList<>();
            collectDirs(root, dirs, 0);
            dirs.remove(source);

            final android.app.Activity host = getActivity();
            if (host == null) {
                return;
            }
            final CharSequence[] labels = new CharSequence[dirs.size()];
            for (int i = 0; i < dirs.size(); i++) {
                final String abs = dirs.get(i).getAbsolutePath();
                final String rel = abs.startsWith(root.getAbsolutePath())
                        ? abs.substring(root.getAbsolutePath().length()).replaceFirst("^/", "")
                        : abs;
                labels[i] = rel.isEmpty() ? root.getName() : rel;
            }

            host.runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }
                if (dirs.isEmpty()) {
                    toast(R.string.tree_no_move_targets);
                    return;
                }
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.move)
                        .setItems(labels, (d, which) -> doMove(file, dirs.get(which)))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }).start();
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

    /** Forget the directories a change touched, then redraw. */
    private void refreshAfterChange(final File a, final File b) {
        _store.invalidate(a);
        if (b != null) {
            _store.invalidate(b);
        }
        rebuild();
    }

    /** Every folder at or under {@code dir}, depth-capped so a stray deep tree cannot hang this. */
    private void collectDirs(final File dir, final List<File> out, final int depth) {
        if (depth > 6 || out.size() > 2000) {
            return;
        }
        out.add(dir);
        final File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (final File k : kids) {
            if (k.isDirectory() && !k.getName().startsWith(".")) {
                collectDirs(k, out, depth + 1);
            }
        }
    }

    private void toast(final int stringRes) {
        if (isAdded()) {
            android.widget.Toast.makeText(requireContext(), stringRes,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
