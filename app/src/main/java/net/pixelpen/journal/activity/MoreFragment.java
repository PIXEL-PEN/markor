/*#######################################################
 *
 *   Maintained 2018-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.pixelpen.journal.activity;


import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import net.pixelpen.journal.R;
import net.gsantner.opoc.frontend.base.GsFragmentBase;
import net.gsantner.opoc.frontend.base.GsPreferenceFragmentBase;
import net.gsantner.opoc.model.GsSharedPreferencesPropertyBackend;
import net.gsantner.opoc.util.GsContextUtils;

/**
 * The fourth tab. It carries a gear icon and now shows the real settings, the same
 * preference tree SettingsActivity shows.
 *
 * <p>It used to host MoreInfoFragment, a short screen whose first row was a shortcut to
 * SettingsActivity, so the gear in the bottom bar and Settings in the overflow menu landed
 * in different places and the tab was a waiting room rather than a destination. The about
 * and licence rows MoreInfoFragment owned are now a category at the foot of
 * preferences_master.xml, and that fragment is gone.
 */
public class MoreFragment extends GsFragmentBase<GsSharedPreferencesPropertyBackend, GsContextUtils> {
    public static final String FRAGMENT_TAG = "MoreFragment";

    public static MoreFragment newInstance() {
        return new MoreFragment();
    }

    public MoreFragment() {
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.more__fragment;
    }

    @Override
    public String getFragmentTag() {
        return FRAGMENT_TAG;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (_savedInstanceState == null) {
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.more__fragment__placeholder_fragment,
                            new SettingsActivity.SettingsFragmentMaster(),
                            SettingsActivity.SettingsFragmentMaster.TAG)
                    .commit();
        }
    }

    /**
     * Back inside a preference sub-screen goes up a level rather than out of the app.
     *
     * <p>SettingsActivity does this in its own onBackPressed. In the tab the press arrives
     * at MainActivity, which offers it to the visible fragment first, so the same walk up
     * has to happen here or Back from Edit mode would leave the app.
     */
    @Override
    public boolean onBackPressed() {
        final Fragment frag = getChildFragmentManager()
                .findFragmentByTag(SettingsActivity.SettingsFragmentMaster.TAG);
        if (frag instanceof GsPreferenceFragmentBase) {
            @SuppressWarnings("rawtypes")
            final GsPreferenceFragmentBase pref = (GsPreferenceFragmentBase) frag;
            if (pref.canGoBack()) {
                pref.goBack();
                return true;
            }
        }
        return super.onBackPressed();
    }
}
