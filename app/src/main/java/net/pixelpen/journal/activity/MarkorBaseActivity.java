package net.pixelpen.journal.activity;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import net.pixelpen.journal.ApplicationObject;
import net.pixelpen.journal.R;
import net.pixelpen.journal.model.AppSettings;
import net.pixelpen.journal.util.MarkorContextUtils;
import net.gsantner.opoc.frontend.base.GsActivityBase;
import net.gsantner.opoc.frontend.base.GsFragmentBase;

public abstract class MarkorBaseActivity extends GsActivityBase<AppSettings, MarkorContextUtils> {

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
        if (_appSettings.isHideSystemStatusbar()) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    protected boolean onReceiveKeyPress(GsFragmentBase fragment, int keyCode, KeyEvent event) {
        return fragment.onReceiveKeyPress(keyCode, event);
    }

    @Override
    public Integer getNewNavigationBarColor() {
        return _appSettings.getAppThemeName().contains("black") ? Color.BLACK : null;
    }

    @Override
    public Integer getNewActivityBackgroundColor() {
        return _appSettings.getAppThemeName().contains("black") ? Color.BLACK : null;
    }

    @Override
    public AppSettings createAppSettingsInstance(Context applicationContext) {
        return ApplicationObject.settings();
    }

    @Override
    public MarkorContextUtils createContextUtilsInstance(Context applicationContext) {
        return new MarkorContextUtils(applicationContext);
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
