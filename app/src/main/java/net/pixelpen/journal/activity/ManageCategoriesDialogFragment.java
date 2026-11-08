package net.pixelpen.journal.activity;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import net.pixelpen.journal.R;
import net.pixelpen.journal.util.CategoryManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Manage categories: a list of what exists, with what can be done to each.
 *
 * <p>This replaces a console. There was an Action spinner -- Add, Rename, Merge, Delete, Undo
 * Last -- over three generic controls named From, Target and New name, whose meaning changed with
 * the verb: in Rename, From was the old name; in Merge, the source; in Delete, the victim. You
 * assembled a sentence from parts, pressed Preview to see a dump of absolute paths, then pressed
 * Confirm. Nothing on screen was the thing you were acting on.
 *
 * <p>Two of the five actions are gone rather than redrawn:
 *
 * <p>Merge was the same operation as deleting a category that still holds notes -- move them
 * somewhere, remove the folder. It is now what deleting a non-empty category asks, which removes
 * the choice between two words for one act, and removes the hard-coded "Uncategorized" that
 * Delete used to invent when its force checkbox was ticked.
 *
 * <p>Undo Last replayed a journal file at the archive root. It was one level deep, named nothing
 * it was about to reverse, and persisted across sessions, so pressing it next week would undo
 * something long forgotten. Every operation here states what it will do before it does it, and
 * the archive has a backup, which is the safety net that can actually be inspected.
 */
public class ManageCategoriesDialogFragment extends DialogFragment {

    /** How the host keeps its own spinner in step. */
    public interface CategoryChangeSink {
        void onCategoryRemoved(String name);

        void onCategoriesChanged();
    }

    public static ManageCategoriesDialogFragment newInstance() {
        return new ManageCategoriesDialogFragment();
    }

    private LinearLayout _list;
    private View _scroll;
    private TextView _empty;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable final Bundle savedInstanceState) {
        final Context ctx = requireContext();
        final View root = LayoutInflater.from(ctx)
                .inflate(R.layout.dialog_manage_categories, null, false);

        _list = root.findViewById(R.id.mc_list);
        _empty = root.findViewById(R.id.mc_empty);
        root.findViewById(R.id.mc_add).setOnClickListener(v -> promptAdd());
        _scroll = root.findViewById(R.id.mc_scroll);

        redraw();

        return new AlertDialog.Builder(ctx)
                .setTitle(R.string.manage_categories)
                .setView(root)
                .setPositiveButton(R.string.done, null)
                .create();
    }

    // ------------------------------------------------------------------ list

    private List<String> categories(final Context ctx) {
        final CategoryManager.Result r = CategoryManager.listCategories(ctx);
        final List<String> out = new ArrayList<>();
        if (r != null && r.changedPaths != null) {
            out.addAll(r.changedPaths);
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /**
     * Rebuild the list, reading the disk off the main thread.
     *
     * <p>This used to do all of it inline, and it was not one scan but many: listCategories
     * walks the archive once, and then countNotes walked it again for every category in the
     * loop. With a handful of test categories that was invisible. With a real archive copied
     * in it is why the dialog took a visible moment to appear.
     *
     * <p>The counts are gathered with the names, in one pass off the thread, and the rows are
     * built from what comes back.
     */
    private void redraw() {
        if (_list == null) {
            return;
        }
        _list.removeAllViews();
        _empty.setVisibility(View.GONE);
        final Context ctx = requireContext().getApplicationContext();
        new Thread(() -> {
            final List<String> cats = categories(ctx);
            final LinkedHashMap<String, int[]> counts = new LinkedHashMap<>();
            for (final String name : cats) {
                counts.put(name, CategoryManager.countNotes(name));
            }
            final Activity host = getActivity();
            if (host == null) {
                return;
            }
            host.runOnUiThread(() -> populate(counts));
        }).start();
    }

    private void populate(final LinkedHashMap<String, int[]> data) {
        // The scan runs on another thread, so the dialog may be gone by the time it lands.
        if (_list == null || !isAdded()) {
            return;
        }
        _list.removeAllViews();
        _empty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);

        final LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (final java.util.Map.Entry<String, int[]> e : data.entrySet()) {
            final String name = e.getKey();
            final int[] counts = e.getValue();
            final View row = inflater.inflate(R.layout.item_manage_category,
                    (ViewGroup) _list, false);

            ((TextView) row.findViewById(R.id.mc_row_name)).setText(name);
            ((TextView) row.findViewById(R.id.mc_row_detail)).setText(describe(counts));
            row.findViewById(R.id.mc_row_rename).setOnClickListener(v -> promptRename(name));
            row.findViewById(R.id.mc_row_delete).setOnClickListener(v -> promptDelete(name, counts));
            _list.addView(row);
        }
        capHeight();
    }

    /**
     * Keep the list short enough that the Add row below it stays on screen.
     *
     * <p>Called from populate and not from onCreateDialog, which is where it was and where it
     * did nothing: the rows arrive from a background thread, so at creation the list is empty,
     * it measured a few pixels, and nothing was ever clamped. The list then filled and pushed
     * Add off the bottom again, which is the bug this was supposed to fix.
     */
    private void capHeight() {
        if (_scroll == null) {
            return;
        }
        _scroll.post(() -> {
            if (_scroll == null || !isAdded()) {
                return;
            }
            final int max = Math.round(getResources().getDisplayMetrics().heightPixels * 0.55f);
            if (_scroll.getHeight() > max) {
                final ViewGroup.LayoutParams lp = _scroll.getLayoutParams();
                lp.height = max;
                _scroll.setLayoutParams(lp);
            }
        });
    }

    /** "12 notes across 3 months", or what is true instead. */
    private String describe(final int[] counts) {
        final int notes = counts[0];
        final int months = counts[1];
        if (months == 0) {
            // In the list but never written to: no folder exists for it anywhere yet.
            return getString(R.string.category_unused);
        }
        if (notes == 0) {
            return getString(R.string.category_empty);
        }
        final String n = getResources().getQuantityString(R.plurals.notes_count, notes, notes);
        final String m = getResources().getQuantityString(R.plurals.months_count, months, months);
        return getString(R.string.category_detail, n, m);
    }

    // --------------------------------------------------------------- actions

    private void promptAdd() {
        final EditText input = newNameField(null);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.add_category)
                .setView(pad(input))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.add, (d, w) -> {
                    final String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    report(CategoryManager.addCategory(requireContext(), name));
                    changed();
                })
                .show();
    }

    private void promptRename(final String from) {
        final EditText input = newNameField(from);
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.rename_category, from))
                .setView(pad(input))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.rename, (d, w) -> {
                    final String to = input.getText().toString().trim();
                    if (to.isEmpty() || to.equalsIgnoreCase(from)) {
                        return;
                    }
                    report(CategoryManager.renameCategory(requireContext(), from, to, false));
                    changed();
                })
                .show();
    }

    /**
     * Delete, asking where the notes go when there are any.
     *
     * <p>An empty category is a name and nothing else, so it goes without ceremony. One that
     * holds writing cannot be removed without saying what happens to the writing, and the only
     * sensible answers are the other categories -- which is what the old Merge action was, and
     * why it is not a separate action any more.
     */
    private void promptDelete(final String name, final int[] counts) {
        final Context ctx = requireContext();

        if (counts[0] == 0) {
            new AlertDialog.Builder(ctx)
                    .setMessage(getString(R.string.delete_category_confirm, name))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.delete, (d, w) -> {
                        // No notes to rehome, so no destination is needed.
                        final CategoryManager.Result r =
                                CategoryManager.deleteCategory(ctx, name, false, false);
                        // A category that exists only in the list has no folder to remove, which
                        // the file layer reports as a failure. It is still gone from the list.
                        if (!r.ok && counts[1] == 0) {
                            removed(name);
                            changed();
                            return;
                        }
                        report(r);
                        if (r.ok) {
                            removed(name);
                        }
                        changed();
                    })
                    .show();
            return;
        }

        final List<String> others = categories(ctx);
        others.remove(name);
        if (others.isEmpty()) {
            // Nowhere to put them. Better to say so than to invent a folder to hold them.
            new AlertDialog.Builder(ctx)
                    .setMessage(getString(R.string.delete_category_nowhere, name))
                    .setPositiveButton(R.string.done, null)
                    .show();
            return;
        }

        final Spinner pick = new Spinner(ctx);
        pick.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_dropdown_item,
                others));

        final LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        final int p = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(p, p / 2, p, 0);

        final TextView message = new TextView(ctx);
        final String n = getResources().getQuantityString(R.plurals.notes_count, counts[0], counts[0]);
        message.setText(getString(R.string.delete_category_move, name, n));
        message.setPadding(0, 0, 0, p / 2);
        box.addView(message);
        box.addView(pick);

        new AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.delete_category_title, name))
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.move_and_delete, (d, w) -> {
                    final Object target = pick.getSelectedItem();
                    if (target == null) {
                        return;
                    }
                    // Merge is exactly this: move every note into the target, remove the source.
                    report(CategoryManager.mergeCategories(ctx, name, target.toString(), false));
                    removed(name);
                    changed();
                })
                .show();
    }

    // ----------------------------------------------------------------- utils

    private EditText newNameField(final String initial) {
        final EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint(R.string.new_name_hint);
        if (initial != null) {
            input.setText(initial);
            input.setSelection(initial.length());
        }
        return input;
    }

    private View pad(final View v) {
        final LinearLayout box = new LinearLayout(requireContext());
        final int p = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(p, p / 2, p, 0);
        box.addView(v);
        return box;
    }

    /** Say what happened, in the file layer's words, only when something went wrong. */
    private void report(final CategoryManager.Result r) {
        if (r == null) {
            return;
        }
        if (!r.ok) {
            final String msg = (r.message == null) ? "" : r.message;
            Toast.makeText(requireContext(),
                    getString(R.string.category_action_failed, msg).trim(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void changed() {
        redraw();
        final CategoryChangeSink sink = sink();
        if (sink != null) {
            sink.onCategoriesChanged();
        }
    }

    private void removed(final String name) {
        final CategoryChangeSink sink = sink();
        if (sink != null) {
            sink.onCategoryRemoved(name);
        }
    }

    private CategoryChangeSink sink() {
        if (getParentFragment() instanceof CategoryChangeSink) {
            return (CategoryChangeSink) getParentFragment();
        }
        if (getActivity() instanceof CategoryChangeSink) {
            return (CategoryChangeSink) getActivity();
        }
        return null;
    }
}
