/*#######################################################
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.pixelpen.journal.util;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.print.PrintJob;
import android.text.TextUtils;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import net.pixelpen.journal.BuildConfig;
import net.pixelpen.journal.R;
import net.pixelpen.journal.activity.openeditor.OpenEditorQuickNoteActivity;
import net.pixelpen.journal.activity.openeditor.OpenEditorTodoActivity;
import net.pixelpen.journal.activity.openeditor.OpenFromShortcutOrWidgetActivity;
import net.pixelpen.journal.activity.openeditor.OpenShareIntoActivity;
import net.pixelpen.journal.model.Document;
import net.gsantner.opoc.frontend.filebrowser.GsFileBrowserListAdapter;
import net.gsantner.opoc.util.GsContextUtils;
import net.gsantner.opoc.util.GsFileUtils;

import java.io.File;

public class AutumnContextUtils extends GsContextUtils {

    public AutumnContextUtils(@Nullable final Context context) {
        if (context != null) {
            setChooserTitle(context.getString(R.string.share_to_arrow));
        }
    }

    public <T extends GsContextUtils> T applySpecialLaunchersVisibility(final Context context, boolean extraLaunchersEnabled) {
        setLauncherActivityEnabled(context, OpenEditorQuickNoteActivity.class, extraLaunchersEnabled);
        setLauncherActivityEnabled(context, OpenEditorTodoActivity.class, extraLaunchersEnabled);
        setLauncherActivityEnabled(context, OpenShareIntoActivity.class, extraLaunchersEnabled);
        setLauncherActivityEnabledFromString(context, aliasDocumentProcessTextName(), extraLaunchersEnabled);
        return thisp();
    }

    /**
     * Component name of the PROCESS_TEXT activity-alias declared in AndroidManifest.xml.
     * <p>
     * The manifest declares it as ".AliasDocumentProcessText", which the manifest merger
     * qualifies with the manifest namespace, while ComponentName pairs it with the applicationId.
     * Those two happen to match again today (both net.pixelpen.journal), but they did not before
     * this rename and need not in future — a debug build already appends ".dev" to the
     * applicationId while leaving the namespace alone.
     * <p>
     * The previous code hardcoded the literal "net.gsantner.markor.AliasDocumentProcessText",
     * which stopped matching the moment the package moved — silently, since the call site
     * swallows exceptions. BuildConfig is generated into the namespace, so deriving the prefix
     * from it keeps the two sides in step whatever either one is called.
     */
    private static String aliasDocumentProcessTextName() {
        final String simpleName = "AliasDocumentProcessText";
        final Package pkg = BuildConfig.class.getPackage();
        final String prefix = (pkg != null) ? pkg.getName() : null;
        return (prefix == null || prefix.isEmpty()) ? simpleName : prefix + "." + simpleName;
    }

    private static int getIconResForFile(final @NonNull File file) {
        if (file.equals(GsFileBrowserListAdapter.VIRTUAL_STORAGE_POPULAR)) {
            return R.mipmap.ic_shortcut_popular;
        } else if (file.equals(GsFileBrowserListAdapter.VIRTUAL_STORAGE_RECENTS)) {
            return R.mipmap.ic_shortcut_recent;
        } else if (file.equals(GsFileBrowserListAdapter.VIRTUAL_STORAGE_FAVOURITE)) {
            return R.mipmap.ic_shortcut_favourite;
        } else if (file.isDirectory()) {
            return R.mipmap.ic_shortcut_folder;
        } else {
            return R.mipmap.ic_shortcut_file;
        }
    }

    public <T extends GsContextUtils> T createLauncherDesktopShortcut(final Context context, final File file) {
        // This is only allowed to call when direct file access is possible!!
        // So basically only for java.io.File Objects. Virtual files, or content://
        // in private/restricted space won't work - because of missing permission grant when re-launching
        final String title = file != null ? GsFileUtils.getFilenameWithoutExtension(file) : null;
        if (!TextUtils.isEmpty(title)) {
            final int iconRes = getIconResForFile(file);
            final Intent intent = new Intent(context, OpenFromShortcutOrWidgetActivity.class).setData(Uri.fromFile(file));
            createLauncherDesktopShortcut(context, intent, iconRes, title);
        }
        return thisp();
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    @SuppressWarnings("deprecation")
    public PrintJob printOrCreatePdfFromWebview(final WebView webview, Document document, boolean... landscape) {
        String jobName = String.format("%s (%s)", document.title, webview.getContext().getString(R.string.app_name_real));
        return super.print(webview, jobName, landscape);
    }

    public <T extends GsContextUtils> T showMountSdDialog(final Activity activity) {
        showMountSdDialog(activity, R.string.mount_storage, R.string.application_needs_access_to_storage_mount_it, R.drawable.mount_sdcard_help);
        return thisp();
    }

    public static File getIntentFile(final Intent intent) {
        return getIntentFile(intent, null);
    }

    public static File getIntentFile(final Intent intent, final @Nullable Context context) {
        if (intent == null) {
            return null;
        }

        // By extra path
        File file = (File) intent.getSerializableExtra(Document.EXTRA_FILE);

        // By url in data
        if (file == null) {
            try {
                file = new File(intent.getData().getPath());
            } catch (NullPointerException ignored) {
            }
        }

        // By stream etc
        if (file == null && context != null) {
            file = GsContextUtils.extractFileFromIntent(intent, context);
        }

        return file;
    }

    public static File getValidIntentFile(final Intent intent, final File fallback) {
        final File f = getIntentFile(intent, null);
        return GsFileUtils.exists(f) ? f : fallback;
    }

    @Override
    public void startActivity(final Context context, final Intent intent) {
        try {
            super.startActivity(context, intent);
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(context, R.string.error_could_not_open_file, Toast.LENGTH_SHORT).show();
        }
    }
}
