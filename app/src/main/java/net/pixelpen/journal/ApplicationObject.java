/*#######################################################
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.pixelpen.journal;

import android.webkit.WebView;

import androidx.multidex.MultiDexApplication;

import net.pixelpen.journal.model.AppSettings;

public class ApplicationObject extends MultiDexApplication {
    // Make resources not marked as unused
    @SuppressWarnings("unused")
    private static final Object[] unused_ignore = new Object[]
            {R.color.colorPrimary, R.color.icons, R.color.divider, R.plurals.item_selected, R.string.project_page, R.style.AppTheme_Unified};

    private static final int[] unused_ignores = new int[]{
            R.string.appearance, R.string.info, R.string.append_to_witharg, R.string.about, R.string.error_cannot_create_notebook_dir__appspecific, R.string.show_license_of_the_app, R.string.show_third_party_licenses, R.string.open_with, R.string.todo_list, R.string.task, R.string.list, R.string.history, R.string.sync, R.string.update, R.string.clear, R.string.due_date, R.string.current_date, R.string.add_task, R.string.add_x_witharg, R.string.create_note, R.string.back_to_previous_folder, R.string.app_settings, R.string.editor_settings, R.string.remember_last_folder_location_on_startup, R.string.number_of_files_witharg, R.string.main_view, R.string.contexts, R.string.projects, R.string.resources, R.string.vertical_alignment, R.string.horizontal_alignment, R.string.default_, R.string.left, R.string.right, R.string.top, R.string.bottom, R.string.center, R.string.directory, R.string.error_picture_selection, R.string.enable_undo_and_redo_be_patient, R.string.tags,
    };

    private volatile static ApplicationObject _app;
    private volatile static AppSettings _appSettings;

    public static ApplicationObject get() {
        return _app;
    }

    public static AppSettings settings() {
        return _appSettings;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        _app = this;
        _appSettings = new AppSettings().init(getApplicationContext());

        // The chosen theme is applied HERE, before any Activity exists, and nowhere else at
        // startup. This is the fix for dark mode falling apart after a resume or a WebView.
        //
        // AppCompat has two ways to force a night mode onto an Activity. If it knows the mode
        // when the Activity's base context is attached, it wraps that context with
        // createConfigurationContext: a real override, part of the resources' identity, which
        // the platform preserves whenever it re-applies the system configuration. If the mode
        // arrives later -- as it did, from AutumnBaseActivity.onPreCreate, one step after
        // attachBaseContext -- AppCompat can only mutate the Activity's existing Resources in
        // place, and that mutation is precisely what ResourcesManager throws away the next time
        // it pushes the system configuration out: on return from another app, and when a
        // WebView initialises. Everything resolved after that moment -- dialogs, icon tints,
        // navigation colours -- came back in the phone's palette instead of the app's.
        //
        // Following the system never showed it because then no override is needed. Dark on a
        // light phone and light on a dark phone both did, which is the mirror image that pointed
        // here.
        //
        // The throwaway WebView below is the old workaround for WebView resetting resources. It
        // stays: it is harmless, and with the override applied properly it no longer matters.
        _appSettings.applyAppTheme();

        // Per https://stackoverflow.com/a/54191884/4717438
        try {
            new WebView(getApplicationContext());
        } catch (Exception ignored) {
        }
    }
}
