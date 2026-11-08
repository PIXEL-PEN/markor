/*#######################################################
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.pixelpen.journal.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import net.gsantner.opoc.util.GsFileUtils;

import net.pixelpen.journal.ApplicationObject;
import net.pixelpen.journal.R;
import net.pixelpen.journal.activity.openeditor.OpenFromShortcutOrWidgetActivity;
import net.pixelpen.journal.model.AppSettings;
import net.pixelpen.journal.model.Document;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Utilities for app shortcuts.
 * https://developer.android.com/guide/topics/ui/shortcuts
 */
public class ShortcutUtils {

    private static final String ID_PREFIX = "shortcut_";

    private static final int MAX_RECENT_DOCUMENTS = 1;

    private ShortcutUtils() {
    }

    /**
     * Pin a launcher shortcut that opens one note, and say whether the launcher took it.
     *
     * <p>Here rather than in the two callers because the Tree and the editor both offer it.
     *
     * <p>The boolean is the point. The inherited helper calls requestPinShortcut and discards
     * what it returns, so a launcher that quietly declines looks identical to one that worked:
     * nothing appears either way and nothing says which happened. This hands the answer back so
     * the caller can tell the person.
     *
     * <p>The id is derived from the path, so pinning the same note twice replaces the shortcut
     * rather than littering the home screen with copies of it.
     */
    public static boolean pinNote(@NonNull final Context context, final File file) {
        if (file == null || !ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            return false;
        }
        final String title = GsFileUtils.getFilenameWithoutExtension(file);
        final Intent open = new Intent(context, OpenFromShortcutOrWidgetActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.fromFile(file))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        final ShortcutInfoCompat shortcut =
                new ShortcutInfoCompat.Builder(context, ID_PREFIX + file.getAbsolutePath().hashCode())
                        .setIntent(open)
                        .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_shortcut_file))
                        .setShortLabel(title)
                        .setLongLabel(title)
                        .build();
        try {
            return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null);
        } catch (final Exception ignored) {
            // Launchers vary in what they accept and some throw rather than returning false.
            return false;
        }
    }

    /**
     * Update the app shortcuts.
     * The list will contain a link to to-do, QuickNote and recent documents.
     * Due to a limit in the Android API, only a few shortcuts can be displayed.
     *
     * @param context Context
     */
    public static void setShortcuts(@NonNull Context context) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
                return;
            }

            final ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);
            if (shortcutManager == null) {
                return;
            }

            final List<ShortcutInfo> newShortcuts = new ArrayList<>();
            final AppSettings appSettings = ApplicationObject.settings();

            // The To-Do and QuickNote shortcuts were here. Both pointed at a single
            // configurable scratch file, which is Markor's model rather than this one: entries
            // here belong to a date and a category, and Autumn's own quick note writes one.
            // Recent documents (max 1)
            final List<String> recentDocuments = appSettings.getRecentDocuments();
            for (int i = 0; i < Math.min(MAX_RECENT_DOCUMENTS, recentDocuments.size()); i++) {
                final File file = new File(recentDocuments.get(i));

                final Intent openFile = new Intent(context, OpenFromShortcutOrWidgetActivity.class)
                        .setAction(Intent.ACTION_EDIT)
                        .setData(Uri.fromFile(file))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);

                final String name = file.getName();

                newShortcuts.add(new ShortcutInfo.Builder(context, ID_PREFIX + name)
                        .setShortLabel(createShortLabel(name))
                        .setLongLabel(createLongLabel(name))
                        .setIcon(Icon.createWithResource(context, R.mipmap.ic_shortcut_file))
                        .setIntent(openFile)
                        .build());
            }

            shortcutManager.setDynamicShortcuts(newShortcuts);
        } catch (Exception error) {
            error.printStackTrace();
        }
    }

    // Alias for callers that may reference updateShortcuts(...)
    public static void updateShortcuts(@NonNull Context context) {
        setShortcuts(context);
    }

    /**
     * Create a short label. Text will be truncated to 10 characters.
     *
     * @param input Input text
     * @return Truncated input text
     */
    private static String createShortLabel(String input) {
        return (input.length() > 10) ? input.substring(0, 7) + "..." : input;
    }

    /**
     * Create a long label. Text will be truncated to 25 characters.
     *
     * @param input Input text
     * @return Truncated input text
     */
    private static String createLongLabel(String input) {
        return (input.length() > 25) ? input.substring(0, 22) + "..." : input;
    }
}
