package net.gsantner.markor.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CalendarView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import net.gsantner.markor.R;
import net.gsantner.markor.model.CreationIndex;
import net.gsantner.markor.model.Document;

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
import net.gsantner.markor.util.StorageRoots;
import net.gsantner.markor.util.NoteTimestamp;


public class CalendarActivity extends AppCompatActivity
        implements ManageCategoriesDialogFragment.CategoryChangeSink {

    private static final String DEFAULT_CATEGORY = "General";
    private static final String ADD_CATEGORY_ITEM = "➕ Add category…";
    private static final int CATEGORY_MAX_LEN = 40;

    // UI-maintained categories
    private static final String PREFS_NAME = "category_ui_prefs";
    private static final String KEY_UI_LIST = "ui_category_list";               // StringSet
    private static final String KEY_PREFER_UI = "prefer_ui_list";               // boolean
    private static final String KEY_UI_LIST_BACKUP = "ui_category_list_backup"; // StringSet

    private EditText inputNoteTitle;
    private Button btnReset, btnSubmit;
    private Spinner spinnerCategory;
    private ImageButton btnBrowser;
    private CalendarView calendarView;
    private final Calendar selectedCalendarDate = Calendar.getInstance();

    private ArrayAdapter<String> categoryAdapter;
    private int lastRealCategorySelection = 0;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calendar);

        // Views
        inputNoteTitle = findViewById(R.id.input_note_title);
        btnReset = findViewById(R.id.btn_reset);
        btnSubmit = findViewById(R.id.btn_submit);
        spinnerCategory = findViewById(R.id.spinner_category);
        btnBrowser = findViewById(R.id.btn_browser);
        calendarView = findViewById(R.id.calendarView);

        // Header/back
        View headerTap = findViewById(R.id.header_click_target);
        if (headerTap != null) headerTap.setOnClickListener(v -> handleBackFromCalendar());
        ImageButton backBtnHeader = findViewById(R.id.btn_back);
        if (backBtnHeader != null) backBtnHeader.setOnClickListener(v -> handleBackFromCalendar());
        TextView title = findViewById(R.id.title_text);
        if (title != null) title.setText("› Calendar");

        // Gear button
        ImageButton gear = findViewById(R.id.btn_manage_categories_toolbar);
        if (gear != null) {
            gear.setOnClickListener(v -> openManageCategoriesDialog());
            gear.setOnLongClickListener(v -> {
                showCategoryToolsPopup(v);
                return true;
            });
            gear.setLongClickable(true);
        }

// Browser button — click only
        if (btnBrowser != null) {
            btnBrowser.setOnClickListener(v -> {
                android.content.Intent intent =
                        new android.content.Intent(CalendarActivity.this, FolderBrowserActivity.class);
                startActivity(intent);
            });
            btnBrowser.setOnLongClickListener(null);
            btnBrowser.setLongClickable(false);
        }

// Initial categories
        bindCategories(false);

        // Calendar date change
        calendarView.setOnDateChangeListener((view, year, month, dayOfMonth) -> {
            selectedCalendarDate.set(Calendar.YEAR, year);
            selectedCalendarDate.set(Calendar.MONTH, month);
            selectedCalendarDate.set(Calendar.DAY_OF_MONTH, dayOfMonth);
        });

        // Reset
        btnReset.setOnClickListener(v -> {
            inputNoteTitle.setText("");
            List<String> cats = loadCategoriesForSpinner();
            int idxDefault = indexOfIgnoreCase(cats, DEFAULT_CATEGORY);
            spinnerCategory.setSelection(idxDefault >= 0 ? idxDefault : 0, false);
            lastRealCategorySelection = spinnerCategory.getSelectedItemPosition();
            calendarView.setDate(System.currentTimeMillis(), false, true);
            selectedCalendarDate.setTimeInMillis(System.currentTimeMillis());
            Toast.makeText(CalendarActivity.this, "Reset", Toast.LENGTH_SHORT).show();
        });

        // Submit
        btnSubmit.setOnClickListener(v -> createNoteFileAndSeedContent());

        // Open folder browser
        btnBrowser.setOnClickListener(v -> {
            String selectedCategory = spinnerCategory.getSelectedItem() == null
                    ? DEFAULT_CATEGORY
                    : spinnerCategory.getSelectedItem().toString();
            // Pinned to Locale.ENGLISH: this has to match the folder names on disk.
            String selectedMonth = NoteTimestamp.monthFolderName(selectedCalendarDate);

            Intent intent = new Intent(CalendarActivity.this, FolderBrowserActivity.class);
            intent.putExtra("selectedCategory", selectedCategory);
            intent.putExtra("selectedMonth", selectedMonth);
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindCategories(true);
    }

    // === PixelPen canonical root ===
    private java.io.File getAppRoot() {
        return StorageRoots.getWorkingRoot(this);
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
        final String yearStr = NoteTimestamp.yearFolderName(when);
        final String monthFolderName = NoteTimestamp.monthFolderName(when);

        java.io.File appRoot  = getAppRoot();
        java.io.File yearDir  = new java.io.File(appRoot, yearStr);
        java.io.File monthDir = new java.io.File(yearDir, monthFolderName);
        java.io.File catDir   = new java.io.File(monthDir, categoryFolderName);

        if (!catDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            catDir.mkdirs();
        }
        return catDir;
    }

    // Gear popup
    private void showCategoryToolsPopup(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
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
                    Toast.makeText(this, "Spinner list cleared.", Toast.LENGTH_SHORT).show();
                });
                return true;
            case 4:
                List<String> prev = getUiCategoryListBackup();
                if (prev == null || prev.isEmpty()) {
                    Toast.makeText(this, "No previous list found.", Toast.LENGTH_SHORT).show();
                    return true;
                }
                confirmAndRun("Restore the previous category list?", () -> {
                    saveUiCategoryList(prev);
                    setPreferUi(true);
                    bindCategories(false);
                    Toast.makeText(this, "Previous category list restored.", Toast.LENGTH_SHORT).show();
                });
                return true;
            case 3:
                confirmAndRun("Reset categories to defaults?", () -> {
                    backupCurrentUiList();
                    List<String> def = new ArrayList<>(Arrays.asList(getResources().getStringArray(R.array.category_list)));
                    saveUiCategoryList(def);
                    setPreferUi(true);
                    bindCategories(false);
                    Toast.makeText(this, "Categories reset to defaults.", Toast.LENGTH_SHORT).show();
                });
                return true;
        }
        return false;
    }

    private void confirmAndRun(String message, Runnable action) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
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

    private void handleBackFromCalendar() {
        if (!isTaskRoot()) {
            finish();
            return;
        }
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("open_tab", "files");
        startActivity(intent);
        finish();
    }

    @Override
    public void onBackPressed() {
        handleBackFromCalendar();
    }

    /**
     * Open Manage Categories Dialog (no-arg factory to match existing class).
     * Note: This won’t enforce UI-only list inside the dialog until we edit the dialog class.
     */
    private void openManageCategoriesDialog() {
        ManageCategoriesDialogFragment.newInstance()
                .show(getSupportFragmentManager(), "manage_categories");
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
            categoryAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, display);
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
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("New category name");
        input.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(CATEGORY_MAX_LEN) });

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Add Category")
                .setMessage("Choose a concise name (no \\/:*?\"<>|)")
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Add", (d, w) -> {
                    String name = input.getText() == null ? "" : input.getText().toString();
                    if (!isValidCategoryName(name)) {
                        Toast.makeText(this,
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
            categoryAdapter = new ArrayAdapter<>(this, R.layout.spinner_item, display);
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

    private void createNoteFileAndSeedContent() {
        String title = inputNoteTitle.getText().toString().trim();
        if (title.isEmpty()) {
            Toast.makeText(this, "Please enter a note title.", Toast.LENGTH_SHORT).show();
            return;
        }

        String category = spinnerCategory.getSelectedItem() == null
                ? DEFAULT_CATEGORY
                : spinnerCategory.getSelectedItem().toString();

        // Guard against special Add item
        if (ADD_CATEGORY_ITEM.equals(category)) {
            Toast.makeText(this, "Please choose a category.", Toast.LENGTH_SHORT).show();
            return;
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
            Toast.makeText(this, "Failed to create folders.", Toast.LENGTH_SHORT).show();
            return;
        }

        String safeTitle = title.replaceAll("[\\\\/:*?\"<>|]", "_");
        File noteFile = new File(categoryDir, safeTitle + ".md");

        if (noteFile.exists()) {
            Toast.makeText(this, "Note already exists.", Toast.LENGTH_SHORT).show();
            return;
        }

        long createdMillis = calendar.getTimeInMillis();
        CreationIndex cidx = new CreationIndex(this);
        cidx.put(noteFile, createdMillis);
        cidx.save();

        try (FileWriter writer = new FileWriter(noteFile)) {
            writer.write(timestamp + "\n\n");
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Failed to create note.", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Note created: " + noteFile.getName(), Toast.LENGTH_SHORT).show();

        // Open the note in OUR editor.
        //
        // This previously built a FileProvider uri and fired ACTION_EDIT with
        // setPackage("net.gsantner.markor"). Our applicationId is net.pixelpen.markorplus, so
        // that opened the note in upstream Markor when it happened to be installed, and threw an
        // uncaught ActivityNotFoundException when it was not — inside a try block that only
        // caught IOException. Launching DocumentActivity directly is what FolderBrowserActivity
        // already did, and needs no uri permissions at all.
        final Intent intent = new Intent(this, DocumentActivity.class);
        intent.putExtra(Document.EXTRA_FILE, noteFile);
        startActivity(intent);
    }

    // UI list persistence + backup
    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
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
