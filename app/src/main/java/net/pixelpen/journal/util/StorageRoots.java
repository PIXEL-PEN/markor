package net.pixelpen.journal.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import androidx.preference.PreferenceManager;

import net.pixelpen.journal.BuildConfig;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the archive root: /Documents/&lt;BuildConfig.STORAGE_ROOT_NAME&gt;.
 * <p>
 * Debug builds set STORAGE_ROOT_NAME to "Markor Plus (dev)" so that a side-by-side
 * preview install cannot read or write the live archive. Release builds use
 * "Markor Plus". Switching between the two migrates the stored preference
 * automatically, in both directions.
 * <p>
 * TODO (Phase 2): fold this into AppSettings.getNotebookDirectory() so the
 * Journal/Tree screens and Markor's file browser cannot disagree about where
 * notes live. Until then this remains a second, independent source of truth.
 */
public final class StorageRoots {
    private StorageRoots() {
    }

    // NOTE: this is NOT the same key as AppSettings' pref_key__notebook_directory. See TODO above.
    private static final String PREF_KEY_STORAGE_ROOT = "pref_storage_root";

    /**
     * Folder names that are treated as "an archive root this app manages", and are
     * therefore re-pointed at the current build's root rather than left alone.
     * Anything else is assumed to be a deliberate user choice and is respected.
     */
    private static final List<String> MANAGED_ROOT_NAMES = Arrays.asList(
            "markor plus",
            "markor plus (dev)",
            "markor plus (pixelpen)",
            "markor default"
    );

    public static File getWorkingRoot(final Context ctx) {
        final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(ctx);
        final String savedPath = sp.getString(PREF_KEY_STORAGE_ROOT, null);

        final File docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        final File defaultRoot = new File(docs, BuildConfig.STORAGE_ROOT_NAME);

        File root = (savedPath == null || savedPath.trim().isEmpty())
                ? defaultRoot
                : new File(savedPath);

        // Re-point any root this app manages at the current build's root. This is what
        // keeps a debug install out of the release archive (and vice versa) even when a
        // path was persisted by the other build.
        if (isManagedRootName(root.getName())) {
            root = defaultRoot;
        }

        final String abs = root.getAbsolutePath();
        if (!abs.equals(savedPath)) {
            sp.edit().putString(PREF_KEY_STORAGE_ROOT, abs).apply();
        }
        return root;
    }

    private static boolean isManagedRootName(final String name) {
        if (name == null) {
            return false;
        }
        final String needle = name.trim().toLowerCase(Locale.ROOT);
        for (final String candidate : MANAGED_ROOT_NAMES) {
            if (candidate.equals(needle)) {
                return true;
            }
        }
        return false;
    }
}
