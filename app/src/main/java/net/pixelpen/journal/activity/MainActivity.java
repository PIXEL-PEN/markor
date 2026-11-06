/*#######################################################
 *
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.pixelpen.journal.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import net.pixelpen.journal.BuildConfig;
import net.pixelpen.journal.R;
import net.pixelpen.journal.model.Document;
import net.pixelpen.journal.util.AutumnContextUtils;
import net.gsantner.opoc.format.GsSimpleMarkdownParser;
import net.gsantner.opoc.frontend.base.GsFragmentBase;
import net.gsantner.opoc.util.GsContextUtils;
import net.gsantner.opoc.util.GsFileUtils;

import net.pixelpen.journal.model.ArchiveBackup;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;

import other.writeily.widget.WrMarkorWidgetProvider;

import android.widget.Toast;

import net.pixelpen.journal.util.AutumnContextUtils;


public class MainActivity extends AutumnBaseActivity {

    public static boolean IS_DEBUG_ENABLED = false;

    private BottomNavigationView _bottomNav;
    private ViewPager2 _viewPager;
    private CalendarFragment _journal;
    private FolderTreeFragment _tree;
    private YearFragment _year;
    private MoreFragment _more;
    private FloatingActionButton _fab;
    private FloatingActionButton _quickFab;
    /** Toolbar items whose visibility follows the tab. */
    private MenuItem _searchItem;
    private MenuItem _homeItem;
    private MenuItem _timelineItem;
    /** Opens the search screen, on the tabs that cannot draw results themselves. */
    private MenuItem _searchElsewhereItem;
    private MenuItem _backupItem;
    private MenuItem _settingsItem;

    private androidx.appcompat.widget.Toolbar _toolbar;

    private AutumnContextUtils _cu;

    @SuppressLint("SdCardPath")
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        IS_DEBUG_ENABLED |= BuildConfig.IS_TEST_BUILD;

        try {
            //noinspection ResultOfMethodCallIgnored
            _appSettings.getNotebookDirectory().mkdirs();
        } catch (Exception ignored) {
        }

        _cu = new AutumnContextUtils(this);
        setContentView(R.layout.main__activity);

        _bottomNav = findViewById(R.id.bottom_navigation_bar);
        _viewPager = findViewById(R.id.main__view_pager_container);
        _fab = findViewById(R.id.fab_add_new_item);
        _fab.setOnClickListener(this::onClickFab);
        _quickFab = findViewById(R.id.fab_quick_note);
        _quickFab.setOnClickListener(this::onClickQuickFab);



        _viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                onViewPagerPageSelected(position);
            }
        });

        setSupportActionBar(findViewById(R.id.toolbar));
        // optShowRate() was here: a third-party nag that counted launches and then offered a
        // trip to the Play store. An archive that asks its owner for a review is the wrong
        // relationship, and the only thing in the app that reached for a store page.

        // Taps on the bar's empty background are swallowed so they cannot reach anything behind
        // it. The navigation icon and the menu items keep their own listeners, so the search icon
        // on the left is unaffected by this.
        _toolbar = findViewById(R.id.toolbar);
        if (_toolbar != null) {
            _toolbar.setOnClickListener(v -> { });
            _toolbar.setLongClickable(false);
            _toolbar.setHapticFeedbackEnabled(false);
        }

        final com.google.android.material.appbar.AppBarLayout appBar = findViewById(R.id.appbar);
        if (appBar != null) {
            appBar.setOnClickListener(v -> {
                // no-op: ignore taps on the app bar background
            });
        }
// --- end click guard ---


        // Setup viewpager
        _viewPager.setAdapter(new SectionsPagerAdapter(getSupportFragmentManager()));
        _viewPager.setOffscreenPageLimit(4);
        _bottomNav.setOnItemSelectedListener((item) -> {
            _viewPager.setCurrentItem(tabIdToPos(item.getItemId()));
            return true;
        });

        reduceViewpagerSwipeSensitivity();

        // Always on. The setting that switched these also switched the QuickNote and ToDo
        // launchers, which no longer exist; share-into and PROCESS_TEXT are how other apps
        // reach Autumn and are not an optional extra.
        _cu.applySpecialLaunchersVisibility(this, true);

    }

    @Override
    public void onActivityFirstTimeVisible() {
        super.onActivityFirstTimeVisible();
        // The pager already rests on position 0, which is Journal, so only move if the user
        // wants a different tab AND no specific file/folder was passed in by an intent.
        //
        final int startTab = _appSettings.getAppStartupTab();
        if (startTab != R.id.nav_journal && AutumnContextUtils.getValidIntentFile(getIntent(), null) == null) {
            _viewPager.postDelayed(() -> _viewPager.setCurrentItem(tabIdToPos(startTab)), 100);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);

        // Save references to fragments
        try {
            final FragmentManager manager = getSupportFragmentManager();
            // Put and get notebook first. Most important for correct operation.
            manager.putFragment(outState, Integer.toString(R.id.nav_journal), _journal);
            manager.putFragment(outState, Integer.toString(R.id.nav_tree), _tree);
            manager.putFragment(outState, Integer.toString(R.id.nav_year), _year);
            manager.putFragment(outState, Integer.toString(R.id.nav_more), _more);
        } catch (NullPointerException | IllegalStateException ignored) {
            Log.d(MainActivity.class.getName(), "Child fragments null in onSaveInstanceState()");
        }
    }

    @Override
    public void onRestoreInstanceState(final Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        if (savedInstanceState == null) {
            return;
        }

        // Get back references to fragments
        try {
            final FragmentManager manager = getSupportFragmentManager();
            _journal = (CalendarFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_journal));
            _tree = (FolderTreeFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_tree));
            _year = (YearFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_year));
            _more = (MoreFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_more));
        } catch (NullPointerException | IllegalStateException ignored) {
            Log.d(MainActivity.class.getName(), "Child fragment not found in onRestoreInstanceState()");
        }
    }

    // Reduces swipe sensitivity
    // Inspired by https://stackoverflow.com/a/72067439
    private void reduceViewpagerSwipeSensitivity() {
        final int SLOP_MULTIPLIER = 4;
        try {
            final Field ff = ViewPager2.class.getDeclaredField("mRecyclerView");
            ff.setAccessible(true);
            final RecyclerView recyclerView = (RecyclerView) ff.get(_viewPager);
            // Set a constant so we don't continuously reduce this value with every call
            recyclerView.setScrollingTouchSlop(RecyclerView.TOUCH_SLOP_PAGING);
            final Field touchSlopField = RecyclerView.class.getDeclaredField("mTouchSlop");
            touchSlopField.setAccessible(true);
            final int touchSlop = (int) touchSlopField.get(recyclerView);
            touchSlopField.set(recyclerView, touchSlop * SLOP_MULTIPLIER);
        } catch (Exception e) {
            Log.d(MainActivity.class.getName(), e.getMessage());
        }
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
    }

    public static void launch(final Activity activity, final File file, final boolean finishfromActivity) {
        if (activity != null && file != null) {
            final Intent intent = new Intent(activity, MainActivity.class);
            intent.putExtra(Document.EXTRA_FILE, file);
            // intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            GsContextUtils.instance.animateToActivity(activity, intent, finishfromActivity, null);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        super.onOptionsItemSelected(item);
        if (item.getItemId() == R.id.action_settings) {
            _cu.animateToActivity(this, SettingsActivity.class, false, null);
            return true;
        }
        if (item.getItemId() == R.id.action_backup) {
            runBackup();
            return true;
        }
        if (item.getItemId() == R.id.action_search_screen) {
            _cu.animateToActivity(this, SearchActivity.class, false, null);
            return true;
        }
        if (item.getItemId() == R.id.action_timeline) {
            _cu.animateToActivity(this, TimelineActivity.class, false, null);
            return true;
        }
        if (item.getItemId() == R.id.action_home) {
            openHomeFolderInAndroid();
            return true;
        }
        return false;
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.main__menu, menu);

        _settingsItem = menu.findItem(R.id.action_settings);
        _homeItem = menu.findItem(R.id.action_home);
        _timelineItem = menu.findItem(R.id.action_timeline);
        _searchElsewhereItem = menu.findItem(R.id.action_search_screen);
        _backupItem = menu.findItem(R.id.action_backup);
        _searchItem = menu.findItem(R.id.action_search);
        final androidx.appcompat.widget.SearchView search =
                (androidx.appcompat.widget.SearchView) _searchItem.getActionView();
        if (search != null) {
            search.setQueryHint(getString(R.string.search));
            search.setOnQueryTextListener(new androidx.appcompat.widget.SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(final String query) {
                    // Searching never moves between screens. The field already matched as it was
                    // typed, so there is nothing for the keyboard's search key to trigger beyond
                    // dismissing itself.
                    //
                    // That leaves the Calendar screen with a field whose results have nowhere to
                    // draw, because only the Tree renders them. Giving the Calendar screen its own
                    // results surface is the missing piece, not another jump between tabs.
                    return true;
                }

                @Override
                public boolean onQueryTextChange(final String text) {
                    if (_tree != null) {
                        _tree.setSearch(text);
                    }
                    return true;
                }
            });
            _searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                @Override
                public boolean onMenuItemActionExpand(@NonNull final MenuItem item) {
                    // No tab switch. Opening the field is not a request to go anywhere; it just
                    // opens the field, on whichever screen it was tapped.
                    return true;
                }

                @Override
                public boolean onMenuItemActionCollapse(@NonNull final MenuItem item) {
                    // Closing the field is how the results are dismissed and the tree comes back.
                    if (_tree != null) {
                        _tree.setSearch(null);
                    }
                    return true;
                }
            });
        }
        // The menu is created before the first page callback runs, so the bar is dressed here too.
        applyToolbarForTab(getCurrentPos());

        _cu.tintMenuItems(menu, true, Color.WHITE);
        _cu.setSubMenuIconsVisibility(menu, true);
        return true;
    }

    /**
     * Zip the archive and hand it to the share sheet.
     *
     * <p>No backup folder of its own, no schedule, no sync. The archive is plain markdown in
     * dated folders and is already the most portable form it could take; the only thing missing
     * was a way to pick it all up at once. Where it then goes -- Drive, mail, a cable -- is the
     * share sheet's business and already solved.
     *
     * <p>The zip is built in the cache, not in the archive, so a backup never ends up inside the
     * next backup. Earlier ones are cleared first for the same reason the cache exists: it is
     * scratch space, and the copy that matters is wherever it was just sent.
     */
    private void runBackup() {
        final File archive = _appSettings.getNotebookDirectory();
        if (archive == null || !archive.isDirectory()) {
            android.widget.Toast.makeText(this, R.string.backup_empty,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        final androidx.appcompat.app.AlertDialog dialog =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setMessage(R.string.backup_working)
                        .setCancelable(false)
                        .show();

        new Thread(() -> {
            final File dir = new File(getCacheDir(), "backups");
            ArchiveBackup.clearOld(dir);
            final File dest = new File(dir, ArchiveBackup.fileNameFor(archive));
            int count = -1;
            try {
                count = ArchiveBackup.create(archive, dest, (done, total) -> {
                    if (done % 25 == 0 || done == total) {
                        runOnUiThread(() -> dialog.setMessage(
                                getString(R.string.backup_progress, done, total)));
                    }
                });
            } catch (final Exception e) {
                count = -1;
            }
            final int written = count;
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                dialog.dismiss();
                if (written < 0) {
                    android.widget.Toast.makeText(this, R.string.backup_failed,
                            android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                android.widget.Toast.makeText(this,
                        getString(R.string.backup_done, written),
                        android.widget.Toast.LENGTH_LONG).show();
                _cu.shareStream(this, dest, "application/zip");
            });
        }).start();
    }

    /**
     * Hand the home folder to Android's own file UI.
     *
     * <p>Home is not a view inside this app. Two earlier attempts treated it as one -- rooting the
     * tree at the home folder and collapsing it -- and both were right about the folder and wrong
     * about the screen: what was wanted is the standard Android file and folder browser, showing
     * Documents/Autumn, not Autumn's own styled tree of years and months.
     *
     * <p>Two routes, because device file managers differ. ACTION_VIEW on the folder's document URI
     * opens it for browsing where that is supported. Where it is not, ACTION_OPEN_DOCUMENT_TREE
     * with the same URI as its starting point is the picker itself, which every device has, and
     * shows the same structure. Nothing is done with what the picker returns: this is for looking,
     * and no access beyond the home folder is wanted or asked for.
     */
    /**
     * Open the Autumn folder in the app's own browser.
     *
     * <p>Was a hand-off to whatever file manager the device had, via ACTION_VIEW on a
     * DocumentsContract URI with ACTION_OPEN_DOCUMENT_TREE as the fallback. Both put the archive
     * behind someone else's picker: read-mostly, inconsistent between devices, and no help for
     * the thing this is actually for, which is deleting a folder typed in by mistake.
     */
    private void openHomeFolderInAndroid() {
        if (_searchItem != null && _searchItem.isActionViewExpanded()) {
            _searchItem.collapseActionView();
        }
        FileBrowserActivity.launch(this, null);
    }

    /**
     * Show search and the overflow on the Calendar and Tree screens.
     *
     * <p>An earlier version put search on the toolbar's navigation icon, to get it to the left of
     * the bar. That is the only slot on the left, but it does not survive: with the toolbar set as
     * the support action bar, the action bar owns the navigation affordance and the icon set
     * directly on the toolbar never appeared. So search is an ordinary action item again.
     *
     * <p>Neither appears on More: search would have nowhere to show results, and an overflow
     * offering Settings on the screen that already offers Settings is a second door into the same
     * room.
     */
    private void applyToolbarForTab(final int pos) {
        final boolean onTree = pos == tabIdToPos(R.id.nav_tree);
        // Year joins Journal and Tree. More is the settings screen and needs none of it.
        final boolean wanted = onTree
                || pos == tabIdToPos(R.id.nav_journal)
                || pos == tabIdToPos(R.id.nav_year);

        if (_searchItem != null && !_searchItem.isActionViewExpanded()) {
            // The inline field belongs to the Tree, where matches replace what is on screen.
            _searchItem.setVisible(onTree);
        }
        if (_searchElsewhereItem != null) {
            _searchElsewhereItem.setVisible(wanted && !onTree);
        }
        if (_homeItem != null) {
            _homeItem.setVisible(wanted);
        }
        if (_timelineItem != null) {
            _timelineItem.setVisible(wanted);
        }
        if (_backupItem != null) {
            _backupItem.setVisible(wanted);
        }
        if (_settingsItem != null) {
            _settingsItem.setVisible(wanted);
        }
        if (_searchItem != null && !onTree && _searchItem.isActionViewExpanded()) {
            // Leaving the Tree closes the field, which also clears the query and the results.
            _searchItem.collapseActionView();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!IntroActivity.isFirstStart(this)) {
            StoragePermissionActivity.requestPermissions(this);
        }

        if (_appSettings.isRecreateMainRequired()) {
            // recreate(); // does not remake fragments
            final Intent intent = getIntent();
            overridePendingTransition(0, 0);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            finish();
            overridePendingTransition(0, 0);
            startActivity(intent);
        }

        if (_cu == null) { _cu = new net.pixelpen.journal.util.AutumnContextUtils(this); }

        // Coming back from the editor is the common way to arrive here, and the note that was
        // being edited has almost certainly changed on disk. The page-change callback does not
        // fire on resume -- the pager never moved -- so without this a screen would show the note
        // as it was when the editor opened.
        //
        // Every screen that reads files, not just the Tree. This was written for the Tree alone
        // and the Year and Timeline screens inherited the bug when they arrived: write a year's
        // entry, come back, and the text was simply not there, because nothing had re-read it.
        reloadCurrentTab();

        _cu.setKeepScreenOn(this, _appSettings.isKeepScreenOn());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && _appSettings.isMultiWindowEnabled()) {
            setTaskDescription(new ActivityManager.TaskDescription(getString(R.string.app_name)));
        }

        // Intro dialog and show changelog etc
        final boolean firstStart = IntroActivity.optStart(this);
        try {
            if (!firstStart && _appSettings.isAppCurrentVersionFirstStart(true)) {
                GsSimpleMarkdownParser smp = GsSimpleMarkdownParser.get().setDefaultSmpFilter(GsSimpleMarkdownParser.FILTER_ANDROID_TEXTVIEW);
                String html = "";
                html += smp.parse(getString(R.string.copyright_license_text_official).replace("\n", "  \n"), "").getHtml();
                html += "<br/><br/><br/><big><big>" + getString(R.string.changelog) + "</big></big><br/>" + smp.parse(getResources().openRawResource(R.raw.changelog), "", GsSimpleMarkdownParser.FILTER_ANDROID_TEXTVIEW);
                html += "<br/><br/><br/><big><big>" + getString(R.string.licenses) + "</big></big><br/>" + smp.parse(getResources().openRawResource(R.raw.licenses_3rd_party), "").getHtml();
                _cu.showDialogWithHtmlTextView(this, 0, html);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    /**
     * The capture FAB.
     *
     * <p>This is the one action the app is for, so it sits where Android puts a primary action
     * instead of as a button inside the form. The Calendar screen owns what capturing means --
     * the picked date, the category, the title, the stamp written as line 1 -- so the FAB only
     * asks it to go ahead.
     *
     * <p>It used to open Markor's new-file dialog into the home folder, which wrote an undated
     * file straight into the archive root: no year, no month, no category and, more to the point,
     * no stamp. That stamp is the durable record of when an entry was made, so a note created
     * that way had nothing for creation order to read. The FAB is only shown on the Calendar tab,
     * which is why there is no per-tab branch here.
     */
    public void onClickFab(final View view) {
        if (_journal == null) {
            return;
        }
        final File created = _journal.captureNote();
        if (created == null) {
            // Nothing was written -- no title, or the category was the "add" entry. Stay put, with
            // what was typed still on screen.
            return;
        }
        // The editor is now on top. Move the pager underneath it so backing out of the editor
        // lands on the Tree with the new entry in place, rather than on the capture form that has
        // just been cleared. Writing a note and then seeing where it went is the loop; being
        // returned to a blank form is not.
        // Switch first: the pager creates the Tree fragment on demand, so _tree may not exist
        // until this runs. The reveal is recorded afterwards and sits there unconsumed until the
        // next reload -- which is the one onResume does when the editor is dismissed, by which
        // point the note has whatever was typed into it.
        _viewPager.setCurrentItem(tabIdToPos(R.id.nav_tree), false);
        if (_tree != null) {
            _tree.revealPathTo(created);
        }
    }

    /**
     * Write the note from its title alone and stay on the Calendar.
     *
     * <p>The same file as the + button makes, minus the editor. Titles run to 252 characters so
     * that the tree can be skimmed without opening anything, which means a good many entries are
     * complete the moment the title is typed; those were being shown a document containing one
     * date stamp and nothing else, and finishing meant backing out of it.
     *
     * <p>No tab switch either. The + button jumps to the Tree because the editor has just
     * covered the screen and backing out of it should land somewhere that shows the new note.
     * Nothing has covered anything here, and someone writing title-only notes is usually writing
     * several, so the form stays where it is with the date and category still set.
     */
    public void onClickQuickFab(final View view) {
        if (_journal == null) {
            return;
        }
        final File created = _journal.captureNoteTitleOnly();
        if (created == null) {
            return;
        }
        if (_tree != null) {
            // Not shown now, but the tree should have it next time it draws.
            _tree.revealPathTo(created);
        }
    }

    @Override
    public void onBackPressed() {
        // Check if fragment handled back press
        final GsFragmentBase<?, ?> frag = getPosFragment(getCurrentPos());
        if (frag == null || !frag.onBackPressed()) {
            super.onBackPressed();
        }
    }

    // Tabs: Journal, Tree, Year, Settings. Order must match main__bottom_nav.xml.
    //
    // Notebook (Markor's file browser) was removed on request. Note that it was the only
    // way to rename, move or delete a note -- the Tree is tap-to-open only -- so that
    // capability is currently absent and wants replacing with actions on the Tree.
    public int tabIdToPos(final int id) {
        if (id == R.id.nav_journal) return 0;
        if (id == R.id.nav_tree) return 1;
        if (id == R.id.nav_year) return 2;
        if (id == R.id.nav_more) return 3;
        return 0;
    }

    /**
     * Re-read whatever the visible screen shows, if it reads anything.
     *
     * <p>Called on resume. Journal and More hold no file listing of their own, so they are not
     * here; the three that do are.
     */
    /**
     * The Calendar's category dropdown changed; open that category next time the tree draws.
     *
     * <p>Routed through here rather than the Calendar reaching for the tree itself, because the
     * tree fragment belongs to this activity's pager and may not exist yet.
     */
    public void onCategoryChosen(final java.io.File dir) {
        // The pager builds the Tree on demand, so at app start there may be nothing to tell yet.
        // Held here and handed over when the tab is opened, rather than dropped.
        _pendingTreeCategory = dir;
        if (_tree != null) {
            _tree.expandCategoryOnNextDraw(dir);
            _pendingTreeCategory = null;
        }
    }

    /** A category chosen before the Tree fragment existed. */
    private java.io.File _pendingTreeCategory;

    private void reloadCurrentTab() {
        final int pos = getCurrentPos();
        if (pos == tabIdToPos(R.id.nav_tree) && _tree != null) {
            _tree.reload();
        } else if (pos == tabIdToPos(R.id.nav_year) && _year != null) {
            _year.reload();
        }
    }

    public int tabIdFromPos(final int pos) {
        return _bottomNav.getMenu().getItem(pos).getItemId();
    }

    public int getCurrentPos() {
        return _viewPager.getCurrentItem();
    }

    public String getPosTitle(final int pos) {
        if (pos == 0) return getString(R.string.calendar);
        if (pos == 1) return getString(R.string.tree);
        if (pos == 2) return getString(R.string.year);
        if (pos == 3) return getString(R.string.settings);
        return "";
    }

    public GsFragmentBase<?, ?> getPosFragment(final int pos) {
        if (pos == 0) return _journal;
        if (pos == 1) return _tree;
        if (pos == 2) return _year;
        if (pos == 3) return _more;
        return null;
    }


    public void onViewPagerPageSelected(final int pos) {
        _bottomNav.getMenu().getItem(pos).setChecked(true);

        // Capture is the only primary action among the tabs; the Tree, Year and More screens
        // have none. The timeline's own is on its own screen now.
        if (pos == tabIdToPos(R.id.nav_journal)) {
            _fab.show();
            _quickFab.show();
        } else {
            _fab.hide();
            _quickFab.hide();
        }

        // Rescan the Tree each time it is shown. The pager keeps fragments alive, so without
        // this a note just captured on the Journal tab would not appear here until the fragment
        // was recreated -- breaking capture-then-see-it, which is the whole loop.
        //
        // The Tree is also told which folder the Calendar screen currently points at, so it opens
        // on the category being worked in instead of leaving it collapsed under the month. The
        // Calendar screen is asked for this rather than the Tree guessing, because only it knows
        // the selected date and category -- and a backdated entry should open the month it was
        // filed under, not today's.
        if (pos == tabIdToPos(R.id.nav_journal) && _journal != null) {
            // Arriving at the Calendar from anywhere starts a fresh entry, so the dropdown starts
            // where a fresh entry should: General. Browsing a category in the tree must not
            // quietly become the filing destination for the next thing written.
            _journal.resetCategoryToDefault();
        }

        if (pos == tabIdToPos(R.id.nav_tree) && _tree != null) {
            if (_journal != null) {
                _tree.focusOn(_journal.currentTargetDir());
            }
            if (_pendingTreeCategory != null) {
                _tree.expandCategoryOnNextDraw(_pendingTreeCategory);
                _pendingTreeCategory = null;
            }
            _tree.reload();
        } else if (_tree != null) {
            // Leaving the Tree closes every category. Coming back shows none open: only choosing
            // a category or writing a note reopens one, and neither is what leaving and
            // returning is.
            _tree.collapseCategories();
        }

        // Same reason the Tree reloads: the pager keeps fragments alive, so a note captured on
        // the Calendar screen would not appear in the year until this fragment was recreated.
        if (pos == tabIdToPos(R.id.nav_year) && _year != null) {
            _year.reload();
        }

        applyToolbarForTab(pos);

        setTitle(getPosTitle(pos));
    }


    class SectionsPagerAdapter extends FragmentStateAdapter {

        SectionsPagerAdapter(FragmentManager fragMgr) {
            super(fragMgr, MainActivity.this.getLifecycle());
        }

        @NonNull
        @Override
        public Fragment createFragment(final int pos) {
            final GsFragmentBase<?, ?> frag;
            final int id = tabIdFromPos(pos);
            if (id == R.id.nav_journal) {
                frag = _journal = CalendarFragment.newInstance();
            } else if (id == R.id.nav_tree) {
                frag = _tree = FolderTreeFragment.newInstance();
            } else if (id == R.id.nav_year) {
                frag = _year = YearFragment.newInstance();
            } else if (id == R.id.nav_more) {
                frag = _more = MoreFragment.newInstance();
            } else {
                frag = _more = MoreFragment.newInstance();
            }
            frag.setMenuVisibility(false);
            return frag;
        }

        @Override
        public int getItemCount() {
            return _bottomNav.getMenu().size();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        WrMarkorWidgetProvider.updateLauncherWidgets();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        _cu.extractResultFromActivityResult(this, requestCode, resultCode, data);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return super.onReceiveKeyPress(getPosFragment(getCurrentPos()), keyCode, event) ? true : super.onKeyDown(keyCode, event);
    }
}
