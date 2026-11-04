package net.pixelpen.journal.activity;

import android.content.Context;

import androidx.annotation.Nullable;

import net.pixelpen.journal.ApplicationObject;
import net.pixelpen.journal.model.AppSettings;
import net.pixelpen.journal.util.AutumnContextUtils;
import net.gsantner.opoc.frontend.base.GsFragmentBase;

public abstract class AutumnBaseFragment extends GsFragmentBase<AppSettings, AutumnContextUtils> {
    @Nullable
    @Override
    public AppSettings createAppSettingsInstance(Context applicationContext) {
        return ApplicationObject.settings();
    }

    @Nullable
    @Override
    public AutumnContextUtils createContextUtilsInstance(Context applicationContext) {
        return new AutumnContextUtils(applicationContext);
    }
}
