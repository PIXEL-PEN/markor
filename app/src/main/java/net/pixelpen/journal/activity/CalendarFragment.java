package net.pixelpen.journal.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.annotation.NonNull;
import android.text.InputFilter;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CalendarView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;


import net.pixelpen.journal.R;
import net.pixelpen.journal.model.CreationIndex;
import net.pixelpen.journal.model.Document;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.pixelpen.journal.ApplicationObject;
import net.pixelpen.journal.util.NoteTimestamp;


public class CalendarFragment extends AutumnBaseFragment
        implements ManageCategoriesDialogFragment.CategoryChangeSink {

    public static final String FRAGMENT_TAG = "CalendarFragment";

    public static CalendarFragment newInstance() {
        return new CalendarFragment();
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.fragment_calendar;
    }

    @Override
    public String getFragmentTag() {
        return FRAGMENT_TAG;
    }


    private static final String DEFAULT_CATEGORY = "General";
    private static final String ADD_CATEGORY_ITEM = "➕ Add category…";
    private static final int CATEGORY_MAX_LEN = 40;

    // UI-maintained categories
    private static final String PREFS_NAME = "category_ui_prefs";
    private static final String KEY_UI_LIST = "ui_category_list";               // StringSet
    private static final String KEY_PREFER_UI = "prefer_ui_list";               // boolean
    private static final String KEY_UI_LIST_BACKUP = "ui_category_list_backup"; // StringSet

    private EditText inputNoteTitle;
    private Spinner spinnerCategory;
    private CalendarView calendarView;
    private final Calendar selectedCalendarDate = Calendar.getInstance();

    private ArrayAdapter<String> categoryAdapter;
    private int lastRealCategorySelection = 0;
    @Override
    public void onViewCreated(@NonNull final View view, final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Views
        inputNoteTitle = view.findViewById(R.id.input_note_title);
        spinnerCategory = view.findViewById(R.id.spinner_category);
        calendarView = view.findViewById(R.id.calendarView);

        // Gear button
        ImageButton gear = view.findViewById(R.id.btn_manage_categories_toolbar);
        if (gear != null) {
            gear.setOnClickListener(v -> openManageCategoriesDialog());
            gear.setOnLongClickListener(v -> {
                showCategoryToolsPopup(v);
                return true;
            });
            gear.setLongClickable(true);
        }

// Initial categories
        bindCategories(false);

        // Calendar date change
        calendarView.setOnDateChangeListener((cv, year, month, dayOfMonth) -> {
            selectedCalendarDate.set(Calendar.YEAR, year);
            selectedCalendarDate.set(Calendar.MONTH, month);
            selectedCalendarDate.set(Calendar.DAY_OF_MONTH, dayOfMonth);
        });

        // Reset and Submit used to be buttons in the form here. Submit is now the FAB, and reset
        // happens on its own once a note has been written, which is the only moment the form was
        // ever stale.
    }

    /**
     * Write the entry the form currently describes, and return the note written, or null if
     * nothing was.
     *
     * <p>Called by MainActivity's FAB, which is the primary action for the Calendar tab. The
     * return value is what lets the host move to the Tree and reveal the new note only when there
     * actually is one -- a missing title or a bad category must leave the user on this screen
     * with what they typed.
     */
    public File captureNote() {
        return createNoteFileAndSeedContent();
    }

    /**
     * Put the form back to a blank entry for today.
     *
     * <p>Runs after a note is written rather than on a button, so the next capture does not start
     * out holding the previous one's title, category and date -- which, on a screen whose whole
     * job is a dated entry, is a way to misfile the next one.
     */
    private void resetForm() {
        inputNoteTitle.setText("");
        final List<String> cats = loadCategoriesForSpinner();
        final int idxDefault = indexOfIgnoreCase(cats, DEFAULT_CATEGORY);
        spinnerCategory.setSelection(idxDefault >= 0 ? idxDefault : 0, false);
        lastRealCategorySelection = spinnerCategory.getSelectedItemPosition();
        calendarView.setDate(System.currentTimeMillis(), false, true);
        selectedCalendarDate.setTimeInMillis(System.currentTimeMillis());
    }

    @Override
    public void onResume() {
        super.onResume();
        bindCategories(true);
    }

    // === PixelPen canonical root ===
    /** The single home folder, shared with the file browser and the Folder Tree. */
    private java.io.File getAppRoot() {
        return ApplicationObject.settings().getNotebookDirectory();
    }

    /**
     * Builds/returns: /Documents/&lt;root&gt;/&lt;YYYY&gt;/&lt;MM_MMMM&gt;/&lt;Category&gt;, creating
     * folders if missing.
     * <p>
     * Folder names come from NoteTimestamp and are pinned to Locale.ENGLISH. They used to be
     * formatted with the device locale, which meant a non-English phone wrote "05_Mai" and then
     * failed to find it again, and changing the phone's language mid-year split one year across
     * two differently-named month folders.
     */
    private java.io.File ensureNoteParentDir(final String categoryFolderName, final Calendar when) {
        final java.io.File catDir = noteParentDir(categoryFolderName, when);
        if (!catDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            catDir.mkdirs();
        }
        return catDir;
    }

    /**
     * The path a note would go to, without creating any of it.
     *
     * <p>Split out of {@link #ensureNoteParentDir} so the Tree can ask where the form currently
     * points and open that folder. Asking the creating version would have had browsing to the
     * Tree quietly litter the archive with empty category folders for dates the user only
     * scrolled past.
     */
    private java.io.File noteParentDir(final String categoryFolderName, final Calendar when) {
        final java.io.File yearDir = new java.io.File(getAppRoot(), NoteTimestamp.yearFolderName(when));
        final java.io.File monthDir = new java.io.File(yearDir, NoteTimestamp.monthFolderName(when));
        return new java.io.File(monthDir, categoryFolderName);
    }

    /**
     * The folder the form as it stands would write into, or null if it does not point anywhere
     * yet -- before the views exist, or while the category spinner is on its "add" entry.
     *
     * <p>The Tree uses this to open on the category being worked in rather than just on the
     * current month.
     */
    public java.io.File currentTargetDir() {
        if (spinnerCategory == null) {
            return null;
        }
        final Object sel = spinnerCategory.getSelectedItem();
        if (sel == null) {
            return null;
        }
        final String category = sel.toString();
        if (ADD_CATEGORY_ITEM.equals(category)) {
            return null;
        }
        return noteParentDir(
                category, NoteTimestamp.combineSelectedDateWithCurrentTime(selectedCalendarDate));
    }

    // Gear popup
    private void showCategoryToolsPopup(View anchor) {
        PopupMenu pm = new PopupMenu(requireContext(), anchor);
        pm.getMenu().add(0, 1, 0, "Clear categories (UI only)");
        pm.getMenu().add(0, 4, 1, "Restore previous list");
        pm.getMenu().add(0, 3, 2, "Reset to defaults");
        pm.setOnMenuItemClickListener(this::onCategoryToolsMenuItem);
        pm.show();
    }

    private boolean onCategoryToolsMenuItem(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                confirmAndRun("Clear all categories from spinner?", () -> {
                    backupCurrentUiList();
                    saveUiCategoryList(new ArrayList<>());
                    setPreferUi(true);
                    bindCategories(false);
                    Toast.makeText(requireContext(), "Spinner list cleared.", Toast.LENGTH_SHORT).show();
                });
                return true;
            case 4:
                List<String> prev = getUiCategoryListBackup();
                if (prev == null || prev.isEmpty()) {
                    Toast.makeText(requireContext(), "No previous list found.", Toast.LENGTH_SHORT).show();
                    return true;
                }
                confirmAndRun("Restore the previous category list?", () -> {
                    saveUiCategoryList(prev);
                    setPreferUi(true);
                    bindCategories(false);
                    Toast.makeText(requireContext(), "Previous category list restored.", Toast.LENGTH_SHORT).show();
                });
                return true;
            case 3:
                confirmAndRun("Reset categories to defaults?", () -> {
                    backupCurrentUiList();
                    List<String> def = new ArrayList<>(Arrays.asList(getResources().getStringArray(R.array.category_list)));
                    saveUiCategoryList(def);
                    setPreferUi(true);
                    bindCategories(false);
                    Toast.makeText(requireContext(), "Categories reset to defaults.", Toast.LENGTH_SHORT).show();
                });
                return true;
        }
        return false;
    }

    private void confirmAndRun(String message, Runnable action) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> action.run())
                .show();
    }

    // Dialog → Activity callbacks
    @Override
    public void onCategoryRemoved(String name) {
        removeCategoryFromSpinnerImmediate(name);
        persistCurrentSpinnerList();
        setPreferUi(true);
    }

    @Override
    public void onCategoriesChanged() {
        bindCategories(true);
        persistCurrentSpinnerList();
        setPreferUi(true);
    }

    // Remove category from spinner immediately
    @SuppressWarnings("unchecked")
    private void removeCategoryFromSpinnerImmediate(String name) {
        if (spinnerCategory == null || spinnerCategory.getAdapter() == null) {
            bindCategories(true);
            return;
        }
        ArrayAdapter<String> ad;
        try {
            ad = (ArrayAdapter<String>) spinnerCategory.getAdapter();
        } catch (ClassCastException e) {
            bindCategories(true);
            return;
        }
        int idxToRemove = -1;
        for (int i = 0; i < ad.getCount(); i++) {
            String item = ad.getItem(i);
            if (item != null && item.equalsIgnoreCase(name)) {
                idxToRemove = i;
                break;
            }
        }
        if (idxToRemove >= 0) {
            ad.remove(ad.getItem(idxToRemove));
            ad.notifyDataSetChanged();
        }
    }

    /**
     * Open Manage Categories Dialog (no-arg factory to match existing class).
     * Note: This won’t enforce UI-only list inside the dialog until we edit the dialog class.
     */
    private void openManageCategoriesDialog() {
        ManageCategoriesDialogFragment.newInstance()
                .show(getChildFragmentManager(), "manage_categories");
    }

    // Data sources (UI-only; no filesystem scan here)
    private List<String> loadCategoriesForSpinner() {
        if (preferUi()) {
            List<String> ui = getUiCategoryList();
            if (ui != null) return ui;
        }
        String[] fallback = getResources().getStringArray(R.array.category_list);
        List<String> list = new ArrayList<>(Arrays.asList(fallback));
        Collections.sort(list, String.CASE_INSENSITIVE_ORDER);
        return list;
    }

    private void bindCategories(boolean keepSelection) {
        String previous = null;
        if (keepSelection && spinnerCategory.getAdapter() != null) {
            Object cur = spinnerCategory.getSelectedItem();
            previous = (cur == null) ? null : cur.toString();
        }
        List<String> cats = loadCategoriesForSpinner();

        // Build display list with special "Add…" item appended
        List<String> display = new ArrayList<>(cats);
        display.add(ADD_CATEGORY_ITEM);

        if (categoryAdapter == null) {
            categoryAdapter = new ArrayAdapter<>(requireContext(), R.layout.spinner_item, display);
            categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerCategory.setAdapter(categoryAdapter);
        } else {
            categoryAdapter.clear();
            categoryAdapter.addAll(display);
            categoryAdapter.notifyDataSetChanged();
        }

        attachCategorySpinnerListener();

        if (keepSelection && previous != null) {
            int idxPrev = indexOfIgnoreCase(cats, previous); // search among real categories only
            if (idxPrev >= 0) {
                spinnerCategory.setSelection(idxPrev, false);
                lastRealCategorySelection = idxPrev;
                return;
            }
        }

        int idxDefault = indexOfIgnoreCase(cats, DEFAULT_CATEGORY);
        spinnerCategory.setSelection(idxDefault >= 0 ? idxDefault : 0, false);
        lastRealCategorySelection = spinnerCategory.getSelectedItemPosition();
    }

    private void attachCategorySpinnerListener() {
        spinnerCategory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean firstFire = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (firstFire) { firstFire = false; return; }
                String chosen = (String) parent.getItemAtPosition(position);
                if (ADD_CATEGORY_ITEM.equals(chosen)) {
                    spinnerCategory.setSelection(lastRealCategorySelection, false);
                    showAddCategoryDialog();
                } else {
                    lastRealCategorySelection = position;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { /* no-op */ }
        });
    }

    private void showAddCategoryDialog() {
        final EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setHint("New category name");
        input.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(CATEGORY_MAX_LEN) });

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Add Category")
                .setMessage("Choose a concise name (no \\/:*?\"<>|)")
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Add", (d, w) -> {
                    String name = input.getText() == null ? "" : input.getText().toString();
                    if (!isValidCategoryName(name)) {
                        Toast.makeText(requireContext(),
                                "Invalid or duplicate name.\n(Disallowed: \\/:*?\"<>|, max " + CATEGORY_MAX_LEN + " chars)",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    addCategoryPersistAndSelect(name.trim());
                })
                .show();
    }

    private boolean isValidCategoryName(String name) {
        if (name == null) return false;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return false;
        if (trimmed.length() > CATEGORY_MAX_LEN) return false;
        if (trimmed.matches(".*[\\\\/:*?\"<>|].*")) return false;

        // Case-insensitive uniqueness against current UI list
        List<String> existing = loadCategoriesForSpinner();
        return indexOfIgnoreCase(existing, trimmed) < 0;
    }

    private void addCategoryPersistAndSelect(String newName) {
        List<String> list = loadCategoriesForSpinner();
        list.add(newName);
        try { Collections.sort(list, String.CASE_INSENSITIVE_ORDER); } catch (Throwable ignored) { }

        backupCurrentUiList();              // snapshot before change
        saveUiCategoryList(list);           // persist to UI list
        setPreferUi(true);

        List<String> display = new ArrayList<>(list);
        display.add(ADD_CATEGORY_ITEM);

        if (categoryAdapter == null) {
            categoryAdapter = new ArrayAdapter<>(requireContext(), R.layout.spinner_item, display);
            categoryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerCategory.setAdapter(categoryAdapter);
        } else {
            categoryAdapter.clear();
            categoryAdapter.addAll(display);
            categoryAdapter.notifyDataSetChanged();
        }

        int idxNew = indexOfIgnoreCase(list, newName);
        if (idxNew >= 0) {
            spinnerCategory.setSelection(idxNew, false);
            lastRealCategorySelection = idxNew;
        }
    }

    private static int indexOfIgnoreCase(List<String> list, String target) {
        if (target == null) return -1;
        for (int i = 0; i < list.size(); i++) {
            String s = list.get(i);
            if (s != null && s.equalsIgnoreCase(target)) return i;
        }
        return -1;
    }

    private File createNoteFileAndSeedContent() {
        String title = inputNoteTitle.getText().toString().trim();
        if (title.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter a note title.", Toast.LENGTH_SHORT).show();
            return null;
        }

        String category = spinnerCategory.getSelectedItem() == null
                ? DEFAULT_CATEGORY
                : spinnerCategory.getSelectedItem().toString();

        // Guard against special Add item
        if (ADD_CATEGORY_ITEM.equals(category)) {
            Toast.makeText(requireContext(), "Please choose a category.", Toast.LENGTH_SHORT).show();
            return null;
        }
        // Stamp = the day the user picked, plus the current wall-clock time so that several
        // entries backdated to the same day keep a stable order.
        //
        // The formatting lives in NoteTimestamp and is pinned to Locale.ENGLISH. This string is
        // written to disk as line 1 of the note, which makes it data rather than presentation:
        // formatting it with the device locale produced stamps that could not be read back.
        final Calendar calendar = NoteTimestamp.combineSelectedDateWithCurrentTime(selectedCalendarDate);
        final String timestamp = NoteTimestamp.format(calendar);

        // Year/month folders are derived from the same calendar inside ensureNoteParentDir().
        File categoryDir = ensureNoteParentDir(category, calendar);

        if (!categoryDir.exists() && !categoryDir.mkdirs()) {
            Toast.makeText(requireContext(), "Failed to create folders.", Toast.LENGTH_SHORT).show();
            return null;
        }

        String safeTitle = title.replaceAll("[\\\\/:*?\"<>|]", "_");
        File noteFile = new File(categoryDir, safeTitle + ".md");

        if (noteFile.exists()) {
            Toast.makeText(requireContext(), "Note already exists.", Toast.LENGTH_SHORT).show();
            return null;
        }

        long createdMillis = calendar.getTimeInMillis();
        CreationIndex cidx = new CreationIndex(requireContext());
        cidx.put(noteFile, createdMillis);
        cidx.save();

        try (FileWriter writer = new FileWriter(noteFile)) {
            writer.write(timestamp + "\n\n");
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(requireContext(), "Failed to create note.", Toast.LENGTH_SHORT).show();
            return null;
        }

        Toast.makeText(requireContext(), "Note created: " + noteFile.getName(), Toast.LENGTH_SHORT).show();

        // Clear the form now, while the write is known to have succeeded. Every early return
        // above leaves it untouched on purpose: those are cases where nothing was saved, and
        // wiping what the user typed would lose it.
        resetForm();

        // Open the note in OUR editor.
        //
        // This previously built a FileProvider uri and fired ACTION_EDIT with
        // setPackage("net.gsantner.markor") — upstream Markor's id, not ours. That opened the
        // note in upstream Markor when it happened to be installed, and threw an uncaught
        // ActivityNotFoundException when it was not, inside a try block that only caught
        // IOException. Launching DocumentActivity directly is what FolderBrowserActivity already
        // did, and needs no uri permissions at all.
        final Intent intent = new Intent(requireContext(), DocumentActivity.class);
        intent.putExtra(Document.EXTRA_FILE, noteFile);
        startActivity(intent);

        return noteFile;
    }

    // UI list persistence + backup
    private SharedPreferences prefs() {
        return requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
    }

    private boolean preferUi() {
        return prefs().getBoolean(KEY_PREFER_UI, false);
    }

    private void setPreferUi(boolean prefer) {
        prefs().edit().putBoolean(KEY_PREFER_UI, prefer).apply();
    }

    private void saveUiCategoryList(List<String> list) {
        // Filter out the special Add item if present
        List<String> clean = new ArrayList<>();
        for (String s : list) {
            if (s != null && !ADD_CATEGORY_ITEM.equals(s)) {
                clean.add(s);
            }
        }
        prefs().edit().putStringSet(KEY_UI_LIST, new HashSet<>(clean)).apply();
    }

    private List<String> getUiCategoryList() {
        Set<String> set = prefs().getStringSet(KEY_UI_LIST, null);
        if (set == null) return null;
        List<String> list = new ArrayList<>(set);
        try { Collections.sort(list, String.CASE_INSENSITIVE_ORDER); } catch (Throwable ignored) { }
        return list;
    }

    private void backupCurrentUiList() {
        List<String> current = getUiCategoryList();
        if (current == null) {
            current = loadCategoriesForSpinner();
        }
        Set<String> set = new HashSet<>();
        for (String s : current) {
            if (s != null && !ADD_CATEGORY_ITEM.equals(s)) set.add(s);
        }
        prefs().edit().putStringSet(KEY_UI_LIST_BACKUP, set).apply();
    }

    private List<String> getUiCategoryListBackup() {
        Set<String> set = prefs().getStringSet(KEY_UI_LIST_BACKUP, null);
        if (set == null) return null;
        List<String> list = new ArrayList<>(set);
        try { Collections.sort(list, String.CASE_INSENSITIVE_ORDER); } catch (Throwable ignored) { }
        return list;
    }

    private void persistCurrentSpinnerList() {
        if (categoryAdapter == null) return;
        List<String> cur = new ArrayList<>();
        for (int i = 0; i < categoryAdapter.getCount(); i++) {
            String s = categoryAdapter.getItem(i);
            if (s != null && !ADD_CATEGORY_ITEM.equals(s)) {
                cur.add(s);
            }
        }
        backupCurrentUiList();  // snapshot before overwrite
        saveUiCategoryList(cur);
    }
}
