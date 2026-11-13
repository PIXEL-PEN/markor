package net.pixelpen.journal.activity;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;

import androidx.annotation.Nullable;

import net.pixelpen.journal.ApplicationObject;
import net.pixelpen.journal.R;
import net.pixelpen.journal.model.AppSettings;
import net.pixelpen.journal.util.AutumnContextUtils;
import net.gsantner.opoc.frontend.base.GsActivityBase;
import net.gsantner.opoc.frontend.base.GsFragmentBase;

public abstract class AutumnBaseActivity extends GsActivityBase<AppSettings, AutumnContextUtils> {

    @Override
    protected void onPreCreate(@Nullable Bundle savedInstanceState) {
        super.onPreCreate(savedInstanceState); // _appSettings, _cu gets available
        setTheme(R.style.AppTheme_Unified);
        // Not applied here. The night mode is set in ApplicationObject.onCreate, before any
        // Activity's base context is attached; setting it from an Activity is one step too late
        // and forces AppCompat onto the in-place path the platform reverts. SettingsActivity
        // still calls applyAppTheme when the user changes the theme, which recreates activities
        // through AppCompat properly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setEnterTransition(null);
            getWindow().setExitTransition(null);
        }
        // Only when a language has actually been chosen.
        //
        // Following the system needs no override, and the override is not free: it goes through
        // Resources.updateConfiguration, which rewrites the whole Configuration. Skipping it
        // entirely for the common case removes any chance of it disturbing the theme, and leaves
        // one code path to reason about rather than one that runs for everybody.
        final String language = _appSettings.getLanguage();
        if (language != null && !language.trim().isEmpty()) {
            _cu.setAppLanguage(this, language);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A measurement, not a fix. Debug builds only. If the theme ever comes apart again,
        // `adb logcat -s AutumnTheme` before and after the trigger shows whether it is the
        // Activity's configuration, the Application's, or the default mode that moved --
        // which is the question that was guessed at for far too long.
        if (net.pixelpen.journal.BuildConfig.DEBUG) {
            final int mask = android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            final int activityNight = getResources().getConfiguration().uiMode & mask;
            final int appNight =
                    getApplicationContext().getResources().getConfiguration().uiMode & mask;
            android.util.Log.d("AutumnTheme", getClass().getSimpleName()
                    + " resume: default="
                    + androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
                    + " activity.uiMode.night=" + activityNight
                    + " app.uiMode.night=" + appNight
                    + "  (32 = night, 16 = day; default 2 = dark, 1 = light, -1 = system)");
        }
    }

    protected boolean onReceiveKeyPress(GsFragmentBase fragment, int keyCode, KeyEvent event) {
        return fragment.onReceiveKeyPress(keyCode, event);
    }

    // Both of these used to return Color.BLACK under the Black theme and null otherwise.
    // Black is gone, so null is the only answer either could give; null means "leave it to
    // the theme", which is what they now say directly.
    @Override
    public Integer getNewNavigationBarColor() {
        return null;
    }

    @Override
    public Integer getNewActivityBackgroundColor() {
        return null;
    }

    @Override
    public AppSettings createAppSettingsInstance(Context applicationContext) {
        return ApplicationObject.settings();
    }

    @Override
    public AutumnContextUtils createContextUtilsInstance(Context applicationContext) {
        return new AutumnContextUtils(applicationContext);
    }

    @Override
    public Boolean isFlagSecure() {
        return _appSettings.isDisallowScreenshots();
    }

    @Override
    public boolean onSearchRequested() {
        return false; // never auto-launch search UI
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            return true; // eat hardware SEARCH key
        }
        return super.onKeyDown(keyCode, event);
    }


}
