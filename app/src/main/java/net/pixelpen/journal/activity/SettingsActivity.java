/*#######################################################
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.pixelpen.journal.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import net.pixelpen.journal.ApplicationObject;
import net.pixelpen.journal.R;
import net.pixelpen.journal.model.AppSettings;
import net.gsantner.opoc.format.GsSimpleMarkdownParser;
import net.gsantner.opoc.frontend.base.GsActivityBase;
import net.gsantner.opoc.frontend.base.GsPreferenceFragmentBase;
import net.gsantner.opoc.frontend.settings.GsFontPreferenceCompat;

import java.io.File;
import java.io.IOException;
import java.util.Locale;


public class SettingsActivity extends AutumnBaseActivity {

    @SuppressWarnings("WeakerAccess")
    public static class RESULT {
        public static final int NOCHANGE = -1;
        public static final int CHANGED = 1;
        public static final int RESTART_REQ = 2;
    }

    public static int activityRetVal = RESULT.NOCHANGE;
    private static int iconColor = Color.WHITE;

    protected Toolbar toolbar;

    public void onCreate(Bundle b) {
        // Must be applied before setContentView
        super.onCreate(b);

        // Load UI
        setContentView(R.layout.settings__activity);
        toolbar = findViewById(R.id.toolbar);

        // Custom code
        GsFontPreferenceCompat.additionalyCheckedFolder = new File(_appSettings.getNotebookDirectory(), ".app/fonts");
        iconColor = _cu.rcolor(this, R.color.primary_text);
        toolbar.setTitle(R.string.settings);
        setSupportActionBar(findViewById(R.id.toolbar));
        toolbar.setNavigationIcon(getResources().getDrawable(R.drawable.ic_arrow_back_white_24dp));
        toolbar.setNavigationOnClickListener(view -> SettingsActivity.this.onBackPressed());
        showFragment(SettingsFragmentMaster.TAG, false);
    }

    protected void showFragment(String tag, boolean addToBackStack) {
        String toolbarTitle = getString(R.string.settings);
        GsPreferenceFragmentBase prefFrag = (GsPreferenceFragmentBase) getSupportFragmentManager().findFragmentByTag(tag);
        if (prefFrag == null) {
            switch (tag) {
                case SettingsFragmentMaster.TAG:
                default: {
                    prefFrag = new SettingsFragmentMaster();
                    toolbarTitle = prefFrag.getTitleOrDefault(toolbarTitle);
                    break;
                }
            }
        }
        toolbar.setTitle(toolbarTitle);
        FragmentTransaction t = getSupportFragmentManager().beginTransaction();
        if (addToBackStack) {
            t.addToBackStack(tag);
        }
        t.replace(R.id.settings__activity__fragment_placeholder, prefFrag, tag).commit();
    }

    @Override
    protected void onStop() {
        setResult(activityRetVal);
        super.onStop();
    }

    public static abstract class MarkorSettingsFragment extends GsPreferenceFragmentBase<AppSettings> {
        @Override
        protected AppSettings getAppSettings(Context context) {
            return ApplicationObject.settings();
        }

        @Override
        protected void onPreferenceChanged(SharedPreferences prefs, String key) {
            activityRetVal = RESULT.CHANGED;
        }

        @Override
        @SuppressWarnings("rawtypes")
        protected void onPreferenceScreenChanged(PreferenceFragmentCompat preferenceFragmentCompat, PreferenceScreen preferenceScreen) {
            super.onPreferenceScreenChanged(preferenceFragmentCompat, preferenceScreen);
            final CharSequence title = preferenceScreen.getTitle();
            final Activity activity = getActivity();
            if (activity instanceof GsActivityBase && !TextUtils.isEmpty(title)) {
                ((GsActivityBase<?, ?>) activity).setToolbarText(title);
            }
        }
    }

    @Override
    public void onBackPressed() {
        GsPreferenceFragmentBase prefFrag = (GsPreferenceFragmentBase) getSupportFragmentManager().findFragmentByTag(SettingsFragmentMaster.TAG);
        if (prefFrag != null && prefFrag.canGoBack()) {
            prefFrag.goBack();
            return;
        }
        super.onBackPressed();
    }

    public static class SettingsFragmentMaster extends MarkorSettingsFragment {
        public static final String TAG = "SettingsFragmentMaster";

        @Override
        public int getPreferenceResourceForInflation() {
            return R.xml.preferences_master;
        }

        @Override
        public String getFragmentTag() {
            return TAG;
        }

        @Override
        public void doUpdatePreferences() {
            setPreferenceVisible(R.string.pref_key__is_multi_window_enabled, Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP);
            updateSummary(R.string.pref_key__more_info__project_license, getString(R.string.app_license_name));
            fillBuildInformationSummary();
            // The experimental-features gate hid tab width and line spacing behind a
            // checkbox. They are ordinary editor settings, not experiments, so they are
            // simply visible now and the gate is gone.
        }

        // onPreferenceChanged was overridden for one reason: to re-apply the theme and restart
        // the activity when the Theme preference changed. With that preference gone the
        // override did nothing but fetch a Context it no longer used and call super, so it is
        // removed and the base implementation runs.

        @Override
        @SuppressWarnings({"ConstantConditions", "ConstantIfStatement", "StatementWithEmptyBody"})
        public Boolean onPreferenceClicked(Preference preference, String key, int keyResId) {
            final FragmentManager fragManager = getActivity().getSupportFragmentManager();
            switch (keyResId) {

                case R.string.pref_key__plaintext__reorder_actions:
                case R.string.pref_key__asciidoc__reorder_actions:
                case R.string.pref_key__markdown__reorder_actions:
                case R.string.pref_key__wikitext_reorder_actions:
                case R.string.pref_key__orgmode__reorder_actions:
                case R.string.pref_key__todotxt__reorder_actions: {
                    startActivity(new Intent(getActivity(), ActionButtonSettingsActivity.class).putExtra(ActionButtonSettingsActivity.EXTRA_FORMAT_KEY, keyResId));
                    break;
                }

                // Ported from MoreInfoFragment, which is gone. All four open a local dialog;
                // none of them reaches the network.
                case R.string.pref_key__more_info__project_license: {
                    _cu.showDialogWithRawFileInWebView(getActivity(), "license.txt", R.string.project_license);
                    return true;
                }
                case R.string.pref_key__more_info__open_source_licenses: {
                    try {
                        _cu.showDialogWithHtmlTextView(getActivity(), R.string.licenses, new GsSimpleMarkdownParser().parse(
                                getResources().openRawResource(R.raw.licenses_3rd_party),
                                "", GsSimpleMarkdownParser.FILTER_ANDROID_TEXTVIEW).getHtml());
                    } catch (final IOException e) {
                        e.printStackTrace();
                    }
                    return true;
                }
                case R.string.pref_key__more_info__copy_build_information: {
                    // Copies the row's own summary, which is the build information. Upstream
                    // also opened the changelog here, which is why a row called "Build
                    // information" answered with a release history. It does not any more.
                    _cu.setClipboard(getContext(), preference.getSummary());
                    Toast.makeText(getContext(), R.string.clipboard, Toast.LENGTH_SHORT).show();
                    return true;
                }
            }

            return null;
        }

        /**
         * Build information, assembled into the row's own summary so tapping it copies the
         * text to the clipboard. Ported from MoreInfoFragment. The Flavor line can no longer
         * appear -- there are no product flavours -- but bcstr returns empty and the line is
         * skipped, so it costs nothing to leave the lookup in place.
         */
        private void fillBuildInformationSummary() {
            final Context context = getContext();
            if (context == null) {
                return;
            }
            final Preference pref = findPreference(R.string.pref_key__more_info__copy_build_information);
            if (pref == null || pref.getSummary() != null) {
                return;
            }
            final Locale locale = Locale.getDefault();
            String tmp;
            String summary = String.format(locale, "\n<b>Package:</b> %s\n<b>Version:</b> v%s (%d)",
                    _cu.getAppIdFlavorSpecific(context), _cu.getAppVersionName(context),
                    _cu.bcint(context, "VERSION_CODE", 0));
            summary += (tmp = _cu.bcstr(context, "BUILD_TYPE", "")).isEmpty() ? "" : ("\n<b>Build type:</b> " + tmp);
            summary += (tmp = _cu.bcstr(context, "BUILD_DATE", "")).isEmpty() ? "" : ("\n<b>Build date:</b> " + tmp);
            // Short form, and called Commit rather than VCS Hash. Forty hex characters wrapped
            // across two lines and said nothing to anyone; eight is what identifies a commit in
            // practice and matches the hash the release APKs are named with, so a build on a
            // phone can be matched to one in the Downloads folder.
            //
            // The line below this one used to be the subject of the last commit, which meant a
            // settings screen reading "VCS Msg: Delete Markor's introduction screens". That is
            // a note between developers and no business of anyone reading Settings.
            tmp = _cu.bcstr(context, "GITHASH", "");
            summary += tmp.isEmpty() ? ""
                    : ("\n<b>Commit:</b> " + (tmp.length() > 8 ? tmp.substring(0, 8) : tmp));
            pref.setSummary(_cu.htmlToSpanned(summary.trim().replace("\n", "<br/>")));
        }

        @Override
        public boolean isDividerVisible() {
            return true;
        }
    }
}
