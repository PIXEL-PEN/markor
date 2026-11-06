/*#######################################################
 *
 *   Autumn - journal, archive, timeline
 *   Maintained 2026 by PIXEL-PEN
 *   License: Apache 2.0
 *
#########################################################*/
package net.pixelpen.journal.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.pixelpen.journal.ApplicationObject;
import net.pixelpen.journal.R;

/**
 * Browse the Autumn home folder, one folder at a time.
 *
 * <p>This is not the Tree, and the difference is the whole point. The Tree draws the entire
 * archive at once, indented and collapsible, which is the right shape for seeing what is there
 * and the wrong shape for tidying it: there is no "current folder" to act on, so renaming a
 * mistyped folder or deleting a test one means hunting through an outline. Earlier attempts at
 * this screen reused the Tree's rows and so inherited exactly that, which is why they kept being
 * wrong however the navigation behaved underneath.
 *
 * <p>So: a flat list of one folder's contents, a path line saying where that is, and a step up to
 * the parent. Nothing indents, nothing expands. Tapping a folder replaces the list; Back goes up
 * a level and only leaves once there is nowhere above to go.
 *
 * <p>Scoped to the home folder. There is no reason for a journal to offer the whole filesystem,
 * and Markor's own system-wide browser was the thing this replaces.
 */
public class FileBrowserActivity extends AutumnBaseActivity {

    /** Optional absolute path to open at. Defaults to the home folder. */
    public static final String EXTRA_START = "start";

    private File _home;
    private File _current;
    private TextView _path;
    private TextView _empty;
    private final List<File> _entries = new ArrayList<>();
    private Adapter _adapter;

    public static void launch(final Activity from, final File at) {
        final Intent i = new Intent(from, FileBrowserActivity.class);
        if (at != null) {
            i.putExtra(EXTRA_START, at.getAbsolutePath());
        }
        from.startActivity(i);
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser);

        _home = ApplicationObject.settings().getNotebookDirectory();
        if (_home == null) {
            finish();
            return;
        }
        if (!_home.isDirectory() && !_home.mkdirs()) {
            Toast.makeText(this, R.string.folder_unavailable, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        _path = findViewById(R.id.fb_path);
        _empty = findViewById(R.id.fb_empty);

        final RecyclerView list = findViewById(R.id.fb_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        _adapter = new Adapter();
        list.setAdapter(_adapter);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        File start = _home;
        final String asked = getIntent().getStringExtra(EXTRA_START);
        if (asked != null) {
            final File f = new File(asked);
            if (f.isDirectory() && isInsideHome(f)) {
                start = f;
            }
        }
        open(start);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Something else may have changed the folder while this was in the background.
        if (_current != null) {
            open(_current);
        }
    }

    // ------------------------------------------------------------ navigation

    private boolean isInsideHome(final File f) {
        final String home = _home.getAbsolutePath();
        final String p = f.getAbsolutePath();
        return p.equals(home) || p.startsWith(home + File.separator);
    }

    private void open(final File dir) {
        if (dir == null || !dir.isDirectory() || !isInsideHome(dir)) {
            return;
        }
        _current = dir;

        // Path relative to the home folder, so the line reads as the archive rather than as
        // somewhere on a disk. "Autumn" alone when at the top.
        final String home = _home.getAbsolutePath();
        String shown = _home.getName();
        if (!dir.getAbsolutePath().equals(home)) {
            shown += "  /  " + dir.getAbsolutePath()
                    .substring(home.length() + 1)
                    .replace(File.separator, "  /  ");
        }
        _path.setText(shown);
        setTitle(dir.getAbsolutePath().equals(home) ? _home.getName() : dir.getName());

        _entries.clear();
        final File[] kids = dir.listFiles();
        if (kids != null) {
            for (final File f : kids) {
                // Hidden files are Autumn's own bookkeeping (.nomedia, indexes). Nothing here
                // acts on them, and showing them invites deleting one.
                if (!f.getName().startsWith(".")) {
                    _entries.add(f);
                }
            }
        }
        // Folders first, then files, each alphabetical. The ordering a file manager has.
        Collections.sort(_entries, new Comparator<File>() {
            @Override
            public int compare(final File a, final File b) {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        _empty.setVisibility(_entries.isEmpty() ? View.VISIBLE : View.GONE);
        _adapter.notifyDataSetChanged();
        invalidateOptionsMenu();
    }

    private boolean goUp() {
        if (_current == null || _current.getAbsolutePath().equals(_home.getAbsolutePath())) {
            return false;
        }
        final File parent = _current.getParentFile();
        if (parent == null || !isInsideHome(parent)) {
            return false;
        }
        open(parent);
        return true;
    }

    @Override
    public void onBackPressed() {
        // Back walks up the folders before it leaves the screen, which is what the path line
        // implies and what every file manager does.
        if (!goUp()) {
            super.onBackPressed();
        }
    }

    // ----------------------------------------------------------------- menu

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        menu.add(0, R.id.action_new_folder, 0, R.string.new_folder)
                .setIcon(R.drawable.baseline_create_new_folder_24)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == R.id.action_new_folder) {
            promptNewFolder();
            return true;
        }
        if (item.getItemId() == android.R.id.home) {
            if (!goUp()) {
                finish();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // --------------------------------------------------------------- actions

    private void promptNewFolder() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.new_name_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.new_folder)
                .setView(padded(input))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.create, (d, w) -> {
                    final String name = input.getText().toString().trim();
                    if (!validName(name)) {
                        return;
                    }
                    final File made = new File(_current, name);
                    if (made.exists()) {
                        toast(getString(R.string.name_already_used, name));
                        return;
                    }
                    if (!made.mkdirs()) {
                        toast(getString(R.string.could_not_create, name));
                        return;
                    }
                    open(_current);
                })
                .show();
    }

    private void promptRename(final File target) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(target.getName());
        input.setSelection(target.getName().length());
        new AlertDialog.Builder(this)
                .setTitle(R.string.rename)
                .setView(padded(input))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.rename, (d, w) -> {
                    final String name = input.getText().toString().trim();
                    if (!validName(name) || name.equals(target.getName())) {
                        return;
                    }
                    final File dest = new File(target.getParentFile(), name);
                    if (dest.exists()) {
                        toast(getString(R.string.name_already_used, name));
                        return;
                    }
                    if (!target.renameTo(dest)) {
                        toast(getString(R.string.could_not_rename, target.getName()));
                        return;
                    }
                    open(_current);
                })
                .show();
    }

    private void promptDelete(final File target) {
        final boolean dir = target.isDirectory();
        final int[] contents = dir ? countTree(target) : new int[]{1, 0};
        final String message;
        if (!dir) {
            message = getString(R.string.delete_file_confirm, target.getName());
        } else if (contents[0] == 0 && contents[1] == 0) {
            message = getString(R.string.delete_folder_empty_confirm, target.getName());
        } else {
            // Say how much goes. A folder deleted from here takes everything under it, and the
            // flat list cannot show what that is.
            final String files = getResources()
                    .getQuantityString(R.plurals.files_count, contents[0], contents[0]);
            message = getString(R.string.delete_folder_confirm, target.getName(), files);
        }

        new AlertDialog.Builder(this)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    if (!deleteRecursive(target)) {
                        toast(getString(R.string.could_not_delete, target.getName()));
                    }
                    open(_current);
                })
                .show();
    }

    /** {files, folders} beneath a directory, so a delete can say what it takes with it. */
    private int[] countTree(final File dir) {
        int files = 0;
        int dirs = 0;
        final List<File> stack = new ArrayList<>();
        stack.add(dir);
        while (!stack.isEmpty()) {
            final File[] kids = stack.remove(stack.size() - 1).listFiles();
            if (kids == null) {
                continue;
            }
            for (final File f : kids) {
                if (f.isDirectory()) {
                    dirs++;
                    stack.add(f);
                } else {
                    files++;
                }
            }
        }
        return new int[]{files, dirs};
    }

    private boolean deleteRecursive(final File f) {
        if (f.isDirectory()) {
            final File[] kids = f.listFiles();
            if (kids != null) {
                for (final File k : kids) {
                    deleteRecursive(k);
                }
            }
        }
        return f.delete();
    }

    private void shareFile(final File f) {
        try {
            _cu.shareStream(this, f, net.gsantner.opoc.util.GsFileUtils.getMimeType(f));
        } catch (final Exception e) {
            toast(getString(R.string.could_not_share, f.getName()));
        }
    }

    private void openEntry(final File f) {
        if (f.isDirectory()) {
            open(f);
            return;
        }
        if (net.gsantner.opoc.util.GsFileUtils.isTextFile(f)) {
            // Browsing, so view mode. The top bar toggle switches to editing.
            DocumentActivity.launch(this, f, Boolean.TRUE, null);
            return;
        }
        shareFile(f);
    }

    // ----------------------------------------------------------------- utils

    private boolean validName(final String name) {
        if (name.isEmpty() || name.equals(".") || name.equals("..")
                || name.contains(File.separator)) {
            toast(getString(R.string.invalid_name));
            return false;
        }
        return true;
    }

    private View padded(final View v) {
        final android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        final int p = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(p, p / 2, p, 0);
        box.addView(v);
        return box;
    }

    private void toast(final String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private String describe(final File f) {
        if (f.isDirectory()) {
            final File[] kids = f.listFiles();
            int n = 0;
            if (kids != null) {
                for (final File k : kids) {
                    if (!k.getName().startsWith(".")) {
                        n++;
                    }
                }
            }
            return getResources().getQuantityString(R.plurals.items_count, n, n);
        }
        return Formatter.formatShortFileSize(this, f.length())
                + "  ·  "
                + DateUtils.getRelativeTimeSpanString(this, f.lastModified());
    }

    // --------------------------------------------------------------- adapter

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_file_browser, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull final Holder h, final int position) {
            final File f = _entries.get(position);
            h.name.setText(f.getName());
            h.detail.setText(describe(f));
            h.icon.setImageResource(f.isDirectory()
                    ? R.drawable.ic_folder_gray_24dp : R.drawable.ic_file_gray_24dp);
            h.itemView.setOnClickListener(v -> openEntry(f));
            h.itemView.setOnLongClickListener(v -> {
                showActions(f);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return _entries.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView detail;

        Holder(final View v) {
            super(v);
            icon = v.findViewById(R.id.fb_icon);
            name = v.findViewById(R.id.fb_name);
            detail = v.findViewById(R.id.fb_detail);
        }
    }

    private void showActions(final File f) {
        final List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.rename));
        if (!f.isDirectory()) {
            labels.add(getString(R.string.share));
        }
        labels.add(getString(R.string.delete));

        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    final String picked = labels.get(which);
                    if (picked.equals(getString(R.string.rename))) {
                        promptRename(f);
                    } else if (picked.equals(getString(R.string.share))) {
                        shareFile(f);
                    } else {
                        promptDelete(f);
                    }
                })
                .show();
    }
}
