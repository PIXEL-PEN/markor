package net.pixelpen.journal.activity;

import android.content.Context;

import androidx.annotation.Nullable;

import net.pixelpen.journal.ApplicationObject;
import net.pixelpen.journal.model.AppSettings;
import net.pixelpen.journal.util.MarkorContextUtils;
import net.gsantner.opoc.frontend.base.GsFragmentBase;

public abstract class MarkorBaseFragment extends GsFragmentBase<AppSettings, MarkorContextUtils> {
    @Nullable
    @Override
    public AppSettings createAppSettingsInstance(Context applicationContext) {
        return ApplicationObject.settings();
    }

    @Nullable
    @Override
    public MarkorContextUtils createContextUtilsInstance(Context applicationContext) {
        return new MarkorContextUtils(applicationContext);
    }
}
