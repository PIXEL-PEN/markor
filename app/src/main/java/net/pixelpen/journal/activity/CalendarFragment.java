package net.pixelpen.journal.activity;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;


import net.pixelpen.journal.R;
import net.pixelpen.journal.model.CreationIndex;
import net.pixelpen.journal.model.Document;
import net.pixelpen.journal.model.Quicknote;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.pixelpen.journal.ApplicationObject;
import net.pixelpen.journal.util.CategoryManager;
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
    private View dayCard;
    private TextView dayCardMonth;
    private TextView dayCardNumber;
    private TextView dayCardWeekday;
    private final Calendar selectedCalendarDate = Calendar.getInstance();

    private ArrayAdapter<String> categoryAdapter;
    private int lastRealCategorySelection = 0;
    @Override
    public void onViewCreated(@NonNull final View view, final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Views
        inputNoteTitle = view.findViewById(R.id.input_note_title);
        spinnerCategory = view.findViewById(R.id.spinner_category);
        dayCard = view.findViewById(R.id.day_card);
        dayCardMonth = view.findViewById(R.id.day_card_month);
        dayCardNumber = view.findViewById(R.id.day_card_number);
        dayCardWeekday = view.findViewById(R.id.day_card_weekday);
        bindDayCard();
        dayCard.setOnClickListener(v -> showDatePicker());

        // The clear button is only there when there is something to clear, so an empty field
        // is not carrying a control that would do nothing.
        final ImageButton clearNote = view.findViewById(R.id.btn_clear_note);
        if (clearNote != null && inputNoteTitle != null) {
            clearNote.setOnClickListener(v -> {
                inputNoteTitle.setText("");
                inputNoteTitle.requestFocus();
            });
            inputNoteTitle.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(final CharSequence s, final int a, final int b, final int c) {
                }

                @Override
                public void onTextChanged(final CharSequence s, final int a, final int b, final int c) {
                }

                @Override
                public void afterTextChanged(final Editable e) {
                    clearNote.setVisibility(e.length() == 0 ? View.GONE : View.VISIBLE);
                }
            });
            clearNote.setVisibility(inputNoteTitle.getText().length() == 0 ? View.GONE : View.VISIBLE);
        }

        final View browse = view.findViewById(R.id.btn_browse_tree);
        if (browse != null) {
            browse.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).onClickBrowse(v);
                }
            });
        }

        // Gear button
        ImageButton gear = view.findViewById(R.id.btn_manage_categories_toolbar);
        if (gear != null) {
            // One gesture, one destination. Clear, Reset and Restore used to live in a popup
            // behind a long press here, which is where the adopt action went too and why it
            // was never found. With one list there is nothing left for them to do: the
            // dialog edits the categories and the spinner shows them.
            gear.setOnClickListener(v -> openManageCategoriesDialog());
        }

// Initial categories
        bindCategories(false);


        // Reset and Submit used to be buttons in the form here. Submit is now the FAB, and reset
        // happens on its own once a note has been written, which is the only moment the form was
        // ever stale.
    }

    /**
     * Put the selected date on the card, and give the band the month's colour.
     *
     * <p>Autumn is a journal, not a planner. A month grid answers "which Tuesday is free",
     * which nobody asks of a diary, and it was taking two thirds of the screen to do it. What
     * a journal needs to know is what day it is, so that is what the screen says, and the grid
     * waits behind a swipe for the one case that wants it: filing something you forgot.
     *
     * <p>The band changes colour through the year. It is the only place the app can carry a
     * season without raising its voice, and after a while it tells you roughly where you are
     * before you read the word.
     */
    private void bindDayCard() {
        if (dayCardMonth == null) {
            return;
        }
        final Locale locale = Locale.getDefault();
        dayCardMonth.setText(new SimpleDateFormat("MMMM yyyy", locale)
                .format(selectedCalendarDate.getTime()).toUpperCase(locale));
        dayCardNumber.setText(String.valueOf(selectedCalendarDate.get(Calendar.DAY_OF_MONTH)));
        dayCardWeekday.setText(new SimpleDateFormat("EEEE", locale)
                .format(selectedCalendarDate.getTime()));

        final TypedArray bands = getResources().obtainTypedArray(R.array.month_band_colors);
        try {
            final int month = selectedCalendarDate.get(Calendar.MONTH);
            final int fallback = ContextCompat.getColor(requireContext(), R.color.band_nov);
            // A tint, not twelve copies of the drawable. The band's shape rounds only its top
            // corners; duplicating that shape to vary one colour would be silly.
            dayCardMonth.setBackgroundTintList(ColorStateList.valueOf(
                    bands.getColor(month, fallback)));
        } finally {
            bands.recycle();
        }
    }

    /**
     * Tap the card, get Android's date picker.
     *
     * <p>This was a month grid under the card, revealed by a swipe. Both were wrong. The grid
     * could not be made small enough to sit under the card without the pair of them filling
     * the screen, which is the problem the card was drawn to solve. The swipe fought the
     * ScrollView, and the grab bar advertising it promised a gesture without saying which.
     *
     * <p>The stock dialog costs no layout at all, cannot conflict with a scroll, and is the
     * control everyone already knows. The price is that it follows the system theme rather
     * than Autumn's, which is a fair trade for a thing you open to answer one question and
     * then dismiss.
     */
    private void showDatePicker() {
        final DatePickerDialog dialog = new DatePickerDialog(requireContext(),
                (view, year, month, day) -> {
                    selectedCalendarDate.set(Calendar.YEAR, year);
                    selectedCalendarDate.set(Calendar.MONTH, month);
                    selectedCalendarDate.set(Calendar.DAY_OF_MONTH, day);
                    bindDayCard();
                },
                selectedCalendarDate.get(Calendar.YEAR),
                selectedCalendarDate.get(Calendar.MONTH),
                selectedCalendarDate.get(Calendar.DAY_OF_MONTH));
        dialog.show();
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
        return createNoteFileAndSeedContent(true);
    }

    /**
     * Write the note and stay put.
     *
     * <p>A title can be up to 252 characters, which is long enough that for many entries the
     * title is the whole note. Those still opened the editor on a file holding nothing but a date
     * stamp, so finishing meant backing out of a document that was never wanted. This writes the
     * same file, seeded the same way, and leaves the editor shut.
     */
    public File captureNoteTitleOnly() {
        return createNoteFileAndSeedContent(false);
    }

    /**
     * A quicknote. Trivia: the shop, the salesperson, the thing you will want next week and
     * never again. It goes into the month's shared Quicknotes file rather than a note of its
     * own, newest first, and it takes no category -- see {@link Quicknote} for why that is a
     * decision rather than an omission.
     *
     * <p>The date comes from the Calendar screen's selection, not from the clock, so a
     * quicknote can be put against the day it is about. The time of day is the clock's, which
     * keeps several entries on one day in order.
     *
     * @return the month file written, for the caller to open, or null if nothing was written.
     */
    /** Whether there is anything in the input field worth filing. */
    public boolean hasQuicknoteText() {
        return inputNoteTitle != null && !inputNoteTitle.getText().toString().trim().isEmpty();
    }

    /** The date a quicknote written now would carry, so the bar item and this screen agree. */
    public Calendar quicknoteCalendar() {
        return NoteTimestamp.combineSelectedDateWithCurrentTime(selectedCalendarDate);
    }

    /** The category the spinner is on. For Browse. */
    public File selectedCategoryDir() {
        return currentTargetDir();
    }

    public File captureQuicknote() {
        final String text = inputNoteTitle.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter a quicknote.", Toast.LENGTH_SHORT).show();
            return null;
        }
        final Calendar calendar =
                NoteTimestamp.combineSelectedDateWithCurrentTime(selectedCalendarDate);
        final File written = Quicknote.write(
                ApplicationObject.settings().getNotebookDirectory(), calendar, text);
        if (written == null) {
            Toast.makeText(requireContext(), "Could not write the quicknote.",
                    Toast.LENGTH_SHORT).show();
            return null;
        }
        // Only now, with the write known to have succeeded. The early returns above leave the
        // text where it is on purpose.
        resetForm();
        return written;
    }

    /**
     * Clear the title after a note is written.
     *
     * <p>The title and only the title. This used to put the category back to the default and the
     * date back to today as well, which was wrong twice.
     *
     * <p>It broke the tree. The form is reset before the host switches tabs, so the tree was told
     * the category being worked in was the default while the note had gone somewhere else, and it
     * opened both -- and every later redraw kept re-asserting the wrong one.
     *
     * <p>It was also wrong on its own terms. Writing several entries into one category, or
     * backfilling a run of days in March, means the category and the date are exactly what should
     * survive a save; re-picking them each time is the work, not the convenience. The title is the
     * only field that is genuinely stale once it has been used, because it has become the note.
     */
    private void resetForm() {
        inputNoteTitle.setText("");
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
    // Dialog → Activity callbacks
    @Override
    public void onCategoryRemoved(String name) {
        // The spinner list is stored in preferences and edited without going through
        // CategoryManager at all, so guarding deleteCategory alone would still have let the
        // protected one be dropped from the list the capture screen reads.
        if (CategoryManager.isProtected(requireContext(), name)) {
            Toast.makeText(requireContext(), getString(R.string.category_protected, name),
                    Toast.LENGTH_LONG).show();
            bindCategories(true);
            return;
        }
        // Nothing to remove by hand any more: CategoryManager has taken it out, so rebinding
        // shows what is left.
        bindCategories(true);
    }
    @Override
    public void onCategoriesChanged() {
        bindCategories(true);
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
    /**
     * Every category that exists: the folders on disk, plus any added but not yet used.
     *
     * <p>There used to be two lists. This read a curated copy in preferences while
     * CategoryManager read the archive, and nothing reconciled them, so Manage categories
     * could add, rename and delete all day without the spinner noticing: bindCategories
     * rebuilt from the curated list and persistCurrentSpinnerList wrote the same list
     * straight back. An archive copied in from elsewhere landed entirely on the side the
     * spinner could not see.
     *
     * <p>One list now, and this is it. The dialog edits categories and the spinner shows
     * them, because they are the same thing.
     */
    private List<String> loadCategoriesForSpinner() {
        final List<String> cats = CategoryManager.listCategories(requireContext()).changedPaths;
        if (cats.isEmpty()) {
            // A fresh install has no folders and nothing added, so seed the starting set.
            // Only ever on genuine emptiness: a user who deletes everything but the
            // protected category still has that, so this does not undo their choice.
            for (final String d : getResources().getStringArray(R.array.category_list)) {
                CategoryManager.addCategory(requireContext(), d);
            }
            return CategoryManager.listCategories(requireContext()).changedPaths;
        }
        return cats;
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
        // Seeded, not chosen: record it so the event it raises is not mistaken for a choice.
        _categoryReportedToTree = lastRealCategorySelection;
    }

    /**
     * The position last reported to the tree, so a genuine move can be told from a redraw.
     *
     * <p>Not {@code firstFire}: that flag is re-armed every time the listener is re-attached,
     * which {@link #bindCategories} does on every rebind. It is disarmed by the programmatic
     * setSelection that follows -- but only when that call actually changes the index. Rebinding
     * while the same category is selected fires nothing, leaves the flag armed, and the user's
     * next real choice is swallowed as though it were the programmatic one.
     *
     * <p>Comparing positions needs no such luck. The programmatic setSelection updates this field
     * synchronously, so by the time its event arrives the position already matches and the move
     * reads as what it is: not a choice.
     */
    private int _categoryReportedToTree = -1;

    /**
     * Put the dropdown back to General.
     *
     * <p>Called when this tab is opened from anywhere else. A category is a statement about the
     * entry being written now, not a mode the app stays in: left as it was, a note typed days
     * later lands in whatever was last browsed, which is a filing mistake nothing on screen warns
     * about.
     *
     * <p>The reset is recorded as already-reported, so the selection event it raises is not
     * mistaken for a choice and does not open anything in the tree.
     */
    public void resetCategoryToDefault() {
        if (spinnerCategory == null || spinnerCategory.getAdapter() == null) {
            return;
        }
        final List<String> cats = loadCategoriesForSpinner();
        final int idx = indexOfIgnoreCase(cats, DEFAULT_CATEGORY);
        final int target = (idx >= 0) ? idx : 0;
        if (target >= spinnerCategory.getAdapter().getCount()) {
            return;
        }
        lastRealCategorySelection = target;
        _categoryReportedToTree = target;
        spinnerCategory.setSelection(target, false);
    }

    /** Tell the tree which category to open, if and only if the selection actually moved. */
    private void reportCategoryToTree(final int position) {
        if (position == _categoryReportedToTree) {
            return;
        }
        _categoryReportedToTree = position;
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).onCategoryChosen(currentTargetDir());
        }
    }

    private void attachCategorySpinnerListener() {
        spinnerCategory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean firstFire = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                final String chosen = (String) parent.getItemAtPosition(position);

                // Above the firstFire guard on purpose. That flag is re-armed on every rebind
                // and is only disarmed by a programmatic setSelection that actually moves; a
                // rebind on the already-selected category leaves it armed, and it would eat this.
                // Comparing positions does not need the flag to have been disarmed.
                if (!ADD_CATEGORY_ITEM.equals(chosen)) {
                    reportCategoryToTree(position);
                }

                if (firstFire) { firstFire = false; return; }
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

        // Through CategoryManager, so the dialog and the spinner see the same thing. This
        // used to write a separate preferences list that only the spinner read.
        CategoryManager.addCategory(requireContext(), newName);

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

    private File createNoteFileAndSeedContent(final boolean openEditor) {
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
            // The bullet, when asked for, is the last thing in the file, so the editor's own
            // default cursor position -- the end of the text, since a new file has no stored
            // one -- already puts the caret beside it. Nothing has to place it.
            writer.write(timestamp + "\n\n"
                    + (ApplicationObject.settings().isNewNoteBullet() ? "- " : ""));
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
        // Boolean.FALSE, not a bare intent. A bare intent leaves the mode to
        // getDocumentPreviewState, which now answers view mode, and a brand new empty note
        // would open as a rendered blank page.
        if (openEditor) {
            DocumentActivity.launch(requireActivity(), noteFile, Boolean.FALSE, null);
        }

        return noteFile;
    }

    // UI list persistence + backup
    private SharedPreferences prefs() {
        return requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
    }
}
