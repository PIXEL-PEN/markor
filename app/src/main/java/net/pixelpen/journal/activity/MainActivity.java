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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import other.writeily.widget.WrMarkorWidgetProvider;

import android.widget.Toast;

import net.pixelpen.journal.util.AutumnContextUtils;


public class MainActivity extends AutumnBaseActivity {

    public static boolean IS_DEBUG_ENABLED = false;

    private BottomNavigationView _bottomNav;
    private ViewPager2 _viewPager;
    private CalendarFragment _journal;
    private FolderTreeFragment _tree;
    private MoreFragment _more;
    private FloatingActionButton _fab;
    /** Toolbar items whose visibility follows the tab. */
    private MenuItem _searchItem;
    private MenuItem _homeItem;
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



        _viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                onViewPagerPageSelected(position);
            }
        });

        setSupportActionBar(findViewById(R.id.toolbar));
        optShowRate();

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
        _viewPager.setOffscreenPageLimit(3);
        _bottomNav.setOnItemSelectedListener((item) -> {
            _viewPager.setCurrentItem(tabIdToPos(item.getItemId()));
            return true;
        });

        reduceViewpagerSwipeSensitivity();

        // noinspection PointlessBooleanExpression - Send Test intent
        if (BuildConfig.IS_TEST_BUILD && false) {
            DocumentActivity.launch(this, new File("/sdcard/Documents/mordor/aa-beamer.md"), true, null);
        }

        _cu.applySpecialLaunchersVisibility(this, _appSettings.isSpecialFileLaunchersEnabled());

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

    private void optShowRate() {
        try {
            new com.pixplicity.generate.Rate.Builder(this)
                    .setTriggerCount(4)
                    .setMinimumInstallTime((int) TimeUnit.MINUTES.toMillis(30))
                    .setFeedbackAction(() -> _cu.showGooglePlayEntryForThisApp(MainActivity.this))
                    .build().count().showRequest();
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        super.onOptionsItemSelected(item);
        if (item.getItemId() == R.id.action_settings) {
            _cu.animateToActivity(this, SettingsActivity.class, false, null);
            return true;
        }
        if (item.getItemId() == R.id.action_home) {
            // Offered on the Calendar screen too, and the tree is what shows the archive, so go
            // there before asking it to go to the top.
            if (_searchItem != null && _searchItem.isActionViewExpanded()) {
                _searchItem.collapseActionView();
            }
            if (getCurrentPos() != tabIdToPos(R.id.nav_tree)) {
                _viewPager.setCurrentItem(tabIdToPos(R.id.nav_tree), false);
            }
            // Posted, so it runs after the pager's page-selected callback rather than before it.
            // That callback hands the Tree the folder the Calendar screen points at and reloads,
            // which re-opens the month and category -- undoing a collapse that had already
            // happened. Going last means going home actually sticks.
            _viewPager.post(() -> {
                if (_tree != null) {
                    _tree.goHome();
                }
            });
            return true;
        }
        return false;
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.main__menu, menu);

        _settingsItem = menu.findItem(R.id.action_settings);
        _homeItem = menu.findItem(R.id.action_home);
        _searchItem = menu.findItem(R.id.action_search);
        final androidx.appcompat.widget.SearchView search =
                (androidx.appcompat.widget.SearchView) _searchItem.getActionView();
        if (search != null) {
            search.setQueryHint(getString(R.string.search));
            search.setOnQueryTextListener(new androidx.appcompat.widget.SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(final String query) {
                    // The field already searched as it was typed; swallowing submit just closes
                    // the keyboard instead of re-running the same walk.
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
        final boolean wanted = onTree || pos == tabIdToPos(R.id.nav_journal);

        if (_searchItem != null && !_searchItem.isActionViewExpanded()) {
            _searchItem.setVisible(wanted);
        }
        if (_homeItem != null) {
            _homeItem.setVisible(wanted);
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
        // fire on resume -- the pager never moved -- so without this the Tree would show the
        // note as it was when the editor opened.
        if (_tree != null && getCurrentPos() == tabIdToPos(R.id.nav_tree)) {
            _tree.reload();
        }

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

    @Override
    public void onBackPressed() {
        // Check if fragment handled back press
        final GsFragmentBase<?, ?> frag = getPosFragment(getCurrentPos());
        if (frag == null || !frag.onBackPressed()) {
            super.onBackPressed();
        }
    }

    // Tabs: Journal, Tree, More. Order must match main__bottom_nav.xml.
    //
    // Notebook (Markor's file browser) was removed on request. Note that it was the only
    // way to rename, move or delete a note -- the Tree is tap-to-open only -- so that
    // capability is currently absent and wants replacing with actions on the Tree.
    public int tabIdToPos(final int id) {
        if (id == R.id.nav_journal) return 0;
        if (id == R.id.nav_tree) return 1;
        if (id == R.id.nav_more) return 2;
        return 0;
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
        if (pos == 2) return getString(R.string.more);
        return "";
    }

    public GsFragmentBase<?, ?> getPosFragment(final int pos) {
        if (pos == 0) return _journal;
        if (pos == 1) return _tree;
        if (pos == 2) return _more;
        return null;
    }


    public void onViewPagerPageSelected(final int pos) {
        _bottomNav.getMenu().getItem(pos).setChecked(true);

        // FAB sits on Journal for now. The Tree is read-only, and More is settings.
        if (pos == tabIdToPos(R.id.nav_journal)) {
            _fab.show();
        } else {
            _fab.hide();
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
        if (pos == tabIdToPos(R.id.nav_tree) && _tree != null) {
            if (_journal != null) {
                _tree.focusOn(_journal.currentTargetDir());
            }
            _tree.reload();
        } else if (_tree != null) {
            // Leaving the Tree closes every category, so coming back shows exactly one open: the
            // one the dropdown points at. Cheaper to state than to track.
            _tree.collapseCategories();
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
