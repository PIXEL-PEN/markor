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
        _appSettings.applyAppTheme();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setEnterTransition(null);
            getWindow().setExitTransition(null);
        }
        _cu.setAppLanguage(this, _appSettings.getLanguage());
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
