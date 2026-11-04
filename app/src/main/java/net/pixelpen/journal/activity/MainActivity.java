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
import android.text.TextUtils;
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
import net.pixelpen.journal.frontend.NewFileDialog;
import net.pixelpen.journal.frontend.filebrowser.AutumnFileBrowserFactory;
import net.pixelpen.journal.model.Document;
import net.pixelpen.journal.util.AutumnContextUtils;
import net.gsantner.opoc.format.GsSimpleMarkdownParser;
import net.gsantner.opoc.frontend.base.GsFragmentBase;
import net.gsantner.opoc.frontend.filebrowser.GsFileBrowserFragment;
import net.gsantner.opoc.frontend.filebrowser.GsFileBrowserListAdapter;
import net.gsantner.opoc.frontend.filebrowser.GsFileBrowserOptions;
import net.gsantner.opoc.util.GsContextUtils;
import net.gsantner.opoc.util.GsFileUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import other.writeily.widget.WrMarkorWidgetProvider;

import android.widget.Toast;

import net.pixelpen.journal.util.AutumnContextUtils;


public class MainActivity extends AutumnBaseActivity implements GsFileBrowserFragment.FilesystemFragmentOptionsListener {

    public static boolean IS_DEBUG_ENABLED = false;

    private BottomNavigationView _bottomNav;
    private ViewPager2 _viewPager;
    private GsFileBrowserFragment _notebook;
    private CalendarFragment _journal;
    private FolderTreeFragment _tree;
    private MoreFragment _more;
    private FloatingActionButton _fab;

    private AutumnContextUtils _cu;
    private File _quickSwitchPrevFolder = null;
    private File _startFolder = null, _showFile = null;

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
        _fab.setOnLongClickListener(this::onLongClickFab);



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
        _viewPager.setOffscreenPageLimit(4);
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

        // Determine start folder
        final File fallback = _appSettings.getFolderToLoadByMenuId(_appSettings.getAppStartupFolderMenuId());
        _startFolder = AutumnContextUtils.getValidIntentFile(getIntent(), fallback);
        if (!GsFileUtils.isDirectory(_startFolder)) {
            _showFile = _startFolder;
            _startFolder = _startFolder.getParentFile();
        }
        if (!GsFileUtils.isDirectory(_startFolder)) {
            _startFolder = _appSettings.getNotebookDirectory();
        }
    }

    @Override
    public void onActivityFirstTimeVisible() {
        super.onActivityFirstTimeVisible();
        // The pager already rests on position 0, which is Journal, so only move if the user
        // wants a different tab AND no specific file/folder was passed in by an intent.
        //
        // This used to compare against nav_notebook because Notebook was position 0. It is
        // position 2 now, so leaving that check in place would have jumped to the file browser
        // on every launch -- the opposite of opening on the calendar.
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
            manager.putFragment(outState, Integer.toString(R.id.nav_notebook), _notebook);
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
            _notebook = (GsFileBrowserFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_notebook));
            _journal = (CalendarFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_journal));
            _tree = (FolderTreeFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_tree));
            _more = (MoreFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_more));

            final NewFileDialog nf = (NewFileDialog) manager.findFragmentByTag(NewFileDialog.FRAGMENT_TAG);
            if (nf != null) {
                nf.setCallback(this::newItemCallback);
            }

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
        final File file = AutumnContextUtils.getValidIntentFile(intent, null);
        if (_notebook != null && file != null) {
            _viewPager.setCurrentItem(tabIdToPos(R.id.nav_notebook), false);
            if (GsFileUtils.isDirectory(file)) {
                _notebook.getAdapter().setCurrentFolder(file);
            } else {
                _notebook.getAdapter().showFile(file);
            }
            _notebook.setReloadRequiredOnResume(false);
        }
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

    // Cycle between recent, favourite, and current
    public boolean onLongClickFab(View view) {
        if (_notebook != null) {
            final File current = _notebook.getCurrentFolder();
            final File dest;
            if (GsFileBrowserListAdapter.VIRTUAL_STORAGE_RECENTS.equals(current)) {
                dest = GsFileBrowserListAdapter.VIRTUAL_STORAGE_FAVOURITE;
            } else if (GsFileBrowserListAdapter.VIRTUAL_STORAGE_FAVOURITE.equals(current)) {
                if (_quickSwitchPrevFolder != null) {
                    dest = _quickSwitchPrevFolder;
                } else {
                    dest = GsFileBrowserListAdapter.VIRTUAL_STORAGE_RECENTS;
                }
            } else {
                _quickSwitchPrevFolder = current;
                dest = GsFileBrowserListAdapter.VIRTUAL_STORAGE_FAVOURITE;
            }
            _notebook.getAdapter().setCurrentFolder(dest);
        }
        return true;
    }

    public void onClickFab(final View view) {
        if (_notebook == null || _notebook.getAdapter() == null) {
            return;
        }

        if (!_notebook.getAdapter().isCurrentFolderWriteable()) {
            _notebook.getAdapter().setCurrentFolder(_appSettings.getNotebookDirectory());
            return;
        }

        if (view.getId() == R.id.fab_add_new_item) {
            if (_cu.isUnderStorageAccessFolder(this, _notebook.getCurrentFolder(), true) && _cu.getStorageAccessFrameworkTreeUri(this) == null) {
                _cu.showMountSdDialog(this);
                return;
            }

            NewFileDialog.newInstance(_notebook.getCurrentFolder(), true, this::newItemCallback)
                    .show(getSupportFragmentManager(), NewFileDialog.FRAGMENT_TAG);
        }
    }

    private void newItemCallback(final File file) {
        if (file.isFile()) {
            DocumentActivity.launch(MainActivity.this, file, false, null);
        }
        _notebook.getAdapter().showFile(file);
    }

    @Override
    public void onBackPressed() {
        // Check if fragment handled back press
        final GsFragmentBase<?, ?> frag = getPosFragment(getCurrentPos());
        if (frag == null || !frag.onBackPressed()) {
            super.onBackPressed();
        }
    }

    public String getFileBrowserTitle() {
        final File file = _notebook != null ? _notebook.getCurrentFolder() : null;
        if (file != null && !_appSettings.getNotebookDirectory().equals(file)) {
            return "> " + file.getName();
        } else {
            return getString(R.string.app_name);
        }
    }

    // Tabs are Notebook, More. QuickNote and ToDo were removed: this is a journalling and
    // archiving app, and a scratch pad and a task list were neither used nor wanted here.
    // Phase 4 adds Journal and Tree in their place.
    // Order must match main__bottom_nav.xml. Journal first: the app opens on capture.
    public int tabIdToPos(final int id) {
        if (id == R.id.nav_journal) return 0;
        if (id == R.id.nav_tree) return 1;
        if (id == R.id.nav_notebook) return 2;
        if (id == R.id.nav_more) return 3;
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
        if (pos == 2) return getFileBrowserTitle();
        if (pos == 3) return getString(R.string.more);
        return "";
    }

    public GsFragmentBase<?, ?> getPosFragment(final int pos) {
        if (pos == 0) return _journal;
        if (pos == 1) return _tree;
        if (pos == 2) return _notebook;
        if (pos == 3) return _more;
        return null;
    }

    /**
     * Restores the default toolbar. Used when changing the tab or moving to another activity
     * while {@link GsFileBrowserFragment} action mode is active (e.g. when renaming a file)
     */
    private void restoreDefaultToolbar() {
        GsFileBrowserFragment wrFragment = getNotebook();
        if (wrFragment != null) {
            wrFragment.clearSelection();
        }
    }

    public void onViewPagerPageSelected(final int pos) {
        _bottomNav.getMenu().getItem(pos).setChecked(true);

        // The new-file FAB belongs to the Files tab. Journal has its own Submit button and the
        // Tree is read-only, so showing it there was just a second way to do the wrong thing.
        if (pos == tabIdToPos(R.id.nav_notebook)) {
            _fab.show();
            if (_notebook != null) {
                _cu.showSoftKeyboard(this, false, _notebook.getView());
            }
        } else {
            _fab.hide();
            restoreDefaultToolbar();
        }

        // Rescan the Tree each time it is shown. The pager keeps fragments alive, so without
        // this a note just captured on the Journal tab would not appear here until the fragment
        // was recreated -- breaking capture-then-see-it, which is the whole loop.
        if (pos == tabIdToPos(R.id.nav_tree) && _tree != null) {
            _tree.reload();
        }

        setTitle(getPosTitle(pos));
    }

    private GsFileBrowserOptions.Options _filesystemDialogOptions = null;

    @Override
    public GsFileBrowserOptions.Options getFilesystemFragmentOptions(GsFileBrowserOptions.Options existingOptions) {
        if (_filesystemDialogOptions == null) {
            _filesystemDialogOptions = AutumnFileBrowserFactory.prepareFsViewerOpts(this, false, new GsFileBrowserOptions.SelectionListenerAdapter() {

                @Override
                public void onFsViewerConfig(GsFileBrowserOptions.Options dopt) {
                    dopt.descModtimeInsteadOfParent = true;
                    dopt.rootFolder = _appSettings.getNotebookDirectory();
                    dopt.startFolder = _startFolder;
                    dopt.doSelectMultiple = dopt.doSelectFolder = dopt.doSelectFile = true;
                    dopt.mountedStorageFolder = _cu.getStorageAccessFolder(MainActivity.this);
                }

                @Override
                public void onFsViewerDoUiUpdate(final GsFileBrowserListAdapter adapter) {
                    if (adapter != null && adapter.getCurrentFolder() != null && !TextUtils.isEmpty(adapter.getCurrentFolder().getName())) {
                        _appSettings.setFileBrowserLastBrowsedFolder(adapter.getCurrentFolder());
                        if (getCurrentPos() == tabIdToPos(R.id.nav_notebook)) {
                            setTitle(getFileBrowserTitle());
                        }
                    }

                    if (_showFile != null && adapter != null) {
                        adapter.showFile(_showFile);
                        _showFile = null;
                    }
                }

                @Override
                public void onFsViewerSelected(String request, File file, final Integer lineNumber) {
                    DocumentActivity.launch(MainActivity.this, file, null, lineNumber);
                }
            });
        }
        return _filesystemDialogOptions;
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
                frag = _notebook = GsFileBrowserFragment.newInstance();
            }
            frag.setMenuVisibility(false);
            return frag;
        }

        @Override
        public int getItemCount() {
            return _bottomNav.getMenu().size();
        }
    }

    public GsFileBrowserFragment getNotebook() {
        return _notebook;
    }

    @Override
    protected void onPause() {
        super.onPause();
        WrMarkorWidgetProvider.updateLauncherWidgets();
    }

    @Override
    protected void onStop() {
        super.onStop();
        restoreDefaultToolbar();
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
