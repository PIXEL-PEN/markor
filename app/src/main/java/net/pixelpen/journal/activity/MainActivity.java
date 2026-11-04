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

        androidx.appcompat.widget.Toolbar tb = findViewById(R.id.toolbar);
        if (tb != null) {
            tb.setOnClickListener(v -> {});     // eat stray taps on the bar background
            tb.setLongClickable(false);
            tb.setHapticFeedbackEnabled(false);
        }



        // --- click guard: prevent whole-toolbar taps from triggering global search ---
        final androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            // Swallow empty-area toolbar clicks but keep menu items working
            toolbar.setOnClickListener(v -> {
                // no-op: intentionally ignore generic toolbar clicks
            });
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
        return false;
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.main__menu, menu);
        menu.findItem(R.id.action_settings).setVisible(_appSettings.isShowSettingsOptionInMainToolbar());

        _cu.tintMenuItems(menu, true, Color.WHITE);
        _cu.setSubMenuIconsVisibility(menu, true);
        return true;
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
        if (_journal != null) {
            _journal.captureNote();
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
        if (pos == tabIdToPos(R.id.nav_tree) && _tree != null) {
            _tree.reload();
        }

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
