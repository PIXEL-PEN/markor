package net.pixelpen.journal.activity;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import net.pixelpen.journal.R;
import net.pixelpen.journal.util.CategoryManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ManageCategoriesDialogFragment extends DialogFragment {

    // === Activity callback ===
    public interface CategoryChangeSink {
        void onCategoryRemoved(String name);
        void onCategoriesChanged(); // generic: add/rename/merge/undo
    }

    public static ManageCategoriesDialogFragment newInstance() {
        return new ManageCategoriesDialogFragment();
    }

    // UI widgets
    private Spinner actionSpinner;
    private Spinner fromSpinner;
    private Spinner targetSpinner;
    private EditText inputNewName;
    private TextView previewOutput;
    private CheckBox chkForceMove;

    private enum Action { ADD, RENAME, MERGE, DELETE, UNDO }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        View root = LayoutInflater.from(ctx).inflate(R.layout.dialog_manage_categories, null, false);

        actionSpinner = root.findViewById(R.id.mc_action);
        fromSpinner = root.findViewById(R.id.mc_from);
        targetSpinner = root.findViewById(R.id.mc_target);
        inputNewName = root.findViewById(R.id.mc_new_name);
        previewOutput = root.findViewById(R.id.mc_preview_output);
        chkForceMove = root.findViewById(R.id.mc_force_uncategorized);

        // Actions (order must match getAction())
        ArrayAdapter<String> actions = new ArrayAdapter<>(
                ctx, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Add", "Rename", "Merge", "Delete", "Undo Last"}
        );
        actionSpinner.setAdapter(actions);

        // Categories for From/Target
        ArrayAdapter<String> catAdapter = new ArrayAdapter<>(
                ctx, android.R.layout.simple_spinner_dropdown_item, loadCategoriesForUi(ctx)
        );
        fromSpinner.setAdapter(catAdapter);
        targetSpinner.setAdapter(catAdapter);

        // Text input settings
        inputNewName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        // Toggle UI when action changes
        actionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                setActionUi(getAction());
                previewOutput.setText("");
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        AlertDialog.Builder b = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.manage_categories)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.preview, (d, w) -> doPreview())
                .setPositiveButton(R.string.confirm, (d, w) -> doConfirm());

        return b.create();
    }

    // Map spinner index to Action
    private Action getAction() {
        switch (actionSpinner.getSelectedItemPosition()) {
            case 0: return Action.ADD;
            case 1: return Action.RENAME;
            case 2: return Action.MERGE;
            case 3: return Action.DELETE;
            case 4: return Action.UNDO;
            default: return Action.ADD;
        }
    }

    // --- UI visibility switch (ONLY controls which fields are shown) ---
    private void setActionUi(Action a) {
        switch (a) {
            case ADD:
                fromSpinner.setVisibility(View.GONE);
                targetSpinner.setVisibility(View.GONE);
                inputNewName.setVisibility(View.VISIBLE);
                chkForceMove.setVisibility(View.GONE);
                break;
            case RENAME:
                fromSpinner.setVisibility(View.VISIBLE);   // old name
                targetSpinner.setVisibility(View.GONE);
                inputNewName.setVisibility(View.VISIBLE);  // new name
                chkForceMove.setVisibility(View.GONE);
                break;
            case MERGE:
                fromSpinner.setVisibility(View.VISIBLE);   // source
                targetSpinner.setVisibility(View.VISIBLE); // destination
                inputNewName.setVisibility(View.GONE);
                chkForceMove.setVisibility(View.GONE);
                break;
            case DELETE:
                fromSpinner.setVisibility(View.VISIBLE);   // category to delete
                targetSpinner.setVisibility(View.GONE);
                inputNewName.setVisibility(View.GONE);
                chkForceMove.setVisibility(View.VISIBLE);  // force-delete toggle
                break;
            case UNDO:
                fromSpinner.setVisibility(View.GONE);
                targetSpinner.setVisibility(View.GONE);
                inputNewName.setVisibility(View.GONE);
                chkForceMove.setVisibility(View.GONE);
                break;
        }
    }

    // --- Preview button logic ---
    private void doPreview() {
        Context ctx = requireContext();
        switch (getAction()) {
            case ADD: {
                String name = safeText(inputNewName);
                if (name.isEmpty()) {
                    previewOutput.setText(getString(R.string.invalid_action));
                } else {
                    previewOutput.setText("Will add: " + name);
                }
                return;
            }
            case RENAME: {
                String from = sel(fromSpinner);
                String to = safeText(inputNewName);
                CategoryManager.Result r = CategoryManager.renameCategory(ctx, from, to, /*dryRun=*/true);
                showResult(r);
                return;
            }
            case MERGE: {
                String from = sel(fromSpinner);
                String to = sel(targetSpinner);
                CategoryManager.Result r = CategoryManager.mergeCategories(ctx, from, to, /*dryRun=*/true);
                showResult(r);
                return;
            }
            case DELETE: {
                String name = sel(fromSpinner);
                boolean force = chkForceMove.isChecked();
                CategoryManager.Result r = CategoryManager.deleteCategory(ctx, name, /*dryRun=*/true, /*forceMoveToUncategorized=*/force);
                showResult(r);
                return;
            }
            case UNDO:
                previewOutput.setText(getString(R.string.undo_will_revert_last_op));
        }
    }

    // --- Confirm button logic (performs the action) ---
    private void doConfirm() {
        Context ctx = requireContext();
        CategoryManager.Result r;

        switch (getAction()) {
            case ADD: {
                String name = safeText(inputNewName);
                r = CategoryManager.addCategory(ctx, name);
                showResult(r);
                if (r.ok) notifyChanged(); // full refresh
                break;
            }
            case RENAME: {
                String from = sel(fromSpinner);
                String to = safeText(inputNewName);
                r = CategoryManager.renameCategory(ctx, from, to, /*dryRun=*/false);
                showResult(r);
                if (r.ok) notifyChanged(); // full refresh easiest
                break;
            }
            case MERGE: {
                String from = sel(fromSpinner);
                String to = sel(targetSpinner);
                r = CategoryManager.mergeCategories(ctx, from, to, /*dryRun=*/false);
                showResult(r);
                if (r.ok) notifyChanged(); // full refresh easiest
                break;
            }
            case DELETE: {
                String name = sel(fromSpinner);
                boolean force = chkForceMove.isChecked();

                // Ensure destination exists when forcing moves (idempotent)
                if (force) {
                    CategoryManager.addCategory(ctx, "Uncategorized");
                }

                r = CategoryManager.deleteCategory(
                        ctx,
                        name,
                        /*dryRun=*/false,
                        /*forceMoveToUncategorized=*/force
                );
                showResult(r);

                if (r.ok) {
                    notifyRemoved(name); // tell activity to drop from spinner immediately
                } else {
                    // If FS had nothing to delete, still drop from UI catalog
                    String msg = (r.message == null) ? "" : r.message.toLowerCase(java.util.Locale.ROOT);
                    boolean noFolders = msg.contains("no folder") || (r.previewCount == 0);
                    if (noFolders) {
                        notifyRemoved(name);
                    }
                }
                break;
            }
            case UNDO:
                r = CategoryManager.undoLast(ctx);
                showResult(r);
                if (r.ok) notifyChanged();
                break;
        }
    }

    // --- Activity notifications ---
    private void notifyRemoved(String name) {
        if (getActivity() instanceof CategoryChangeSink) {
            ((CategoryChangeSink) getActivity()).onCategoryRemoved(name);
        } else {
            // fallback: generic changed
            notifyChanged();
        }
        dismissAllowingStateLoss();
    }

    private void notifyChanged() {
        if (getActivity() instanceof CategoryChangeSink) {
            ((CategoryChangeSink) getActivity()).onCategoriesChanged();
        }
        dismissAllowingStateLoss();
    }

    // --- Helpers ---
    private void showResult(CategoryManager.Result r) {
        if (previewOutput == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(r.ok ? "✅ " : "❌ ");
        sb.append(r.message != null ? r.message : "");
        if (r.previewCount > 0) {
            sb.append("\n\n").append(getString(R.string.items)).append(": ").append(r.previewCount);
        }
        if (r.changedPaths != null && !r.changedPaths.isEmpty()) {
            for (String p : r.changedPaths) {
                sb.append("\n• ").append(p);
            }
        }
        previewOutput.setText(sb.toString());
    }

    private static String safeText(EditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private static String sel(Spinner sp) {
        Object o = sp.getSelectedItem();
        return o == null ? "" : String.valueOf(o);
    }

    private List<String> loadCategoriesForUi(Context ctx) {
        List<String> list = new ArrayList<>();
        try {
            CategoryManager.Result r = CategoryManager.listCategories(ctx);
            if (r != null && r.changedPaths != null) list.addAll(r.changedPaths);
        } catch (Throwable ignored) { }
        if (list.isEmpty()) list.add("Uncategorized");
        try { Collections.sort(list, String.CASE_INSENSITIVE_ORDER); } catch (Throwable ignored) { }
        return list;
    }
}
