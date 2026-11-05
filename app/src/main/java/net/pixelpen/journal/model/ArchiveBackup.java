package net.pixelpen.journal.model;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A point-in-time copy of the whole archive, as a zip.
 *
 * <p>Deliberately not a format of its own. The archive is already the best backup it could have --
 * plain markdown in dated folders, readable on any machine with no software required -- so the
 * only thing worth adding is a way to pick it all up at once and hand it somewhere else. A zip
 * preserves the folders and nothing else needs to understand it.
 *
 * <p>Entries are written under the archive folder's own name, so restoring is unzipping into
 * Documents and nothing more. A backup whose restore needs explaining is not much of a backup.
 */
public final class ArchiveBackup {

    private ArchiveBackup() {
    }

    /** {@code Autumn-backup-2026-11-05.zip}. */
    public static String fileNameFor(final File archiveRoot) {
        final String stamp = new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ENGLISH)
                .format(new Date());
        final String name = (archiveRoot == null) ? "archive" : archiveRoot.getName();
        return name.replaceAll("[\\\\/:*?\"<>|\\s]", "_") + "-backup-" + stamp + ".zip";
    }

    /** Reports progress so a long backup can say what it is doing. */
    public interface Progress {
        void onFile(int done, int total);
    }

    /**
     * Zip {@code archiveRoot} into {@code dest}.
     *
     * @return the number of files written.
     * @throws IOException if anything fails; {@code dest} is removed rather than left as a partial
     *                     archive that looks like a backup and is not one.
     */
    public static int create(final File archiveRoot, final File dest, final Progress progress)
            throws IOException {
        if (archiveRoot == null || !archiveRoot.isDirectory()) {
            throw new IOException("No archive to back up");
        }
        final int total = countFiles(archiveRoot);
        final File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }

        ZipOutputStream zip = null;
        try {
            zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(dest)));
            final int[] done = {0};
            // The root's own name leads every entry, so the zip unpacks as "Autumn/..." rather
            // than scattering years loose into whatever folder it is opened in.
            addTree(zip, archiveRoot, archiveRoot.getName(), done, total, progress);
            zip.finish();
            return done[0];
        } catch (final IOException e) {
            closeQuietly(zip);
            zip = null;
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            throw e;
        } finally {
            closeQuietly(zip);
        }
    }

    private static void addTree(final ZipOutputStream zip, final File file, final String path,
                                final int[] done, final int total, final Progress progress)
            throws IOException {
        if (file.isDirectory()) {
            final File[] kids = file.listFiles();
            if (kids == null) {
                return;
            }
            if (kids.length == 0) {
                // Empty folders are kept. In this archive a folder with nothing in it is a
                // statement -- a year laid down and not yet written about -- not an absence.
                zip.putNextEntry(new ZipEntry(path + "/"));
                zip.closeEntry();
                return;
            }
            for (final File k : kids) {
                addTree(zip, k, path + "/" + k.getName(), done, total, progress);
            }
            return;
        }

        zip.putNextEntry(new ZipEntry(path));
        BufferedInputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(file));
            final byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                zip.write(buf, 0, n);
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (final IOException ignore) {
                    // Nothing useful to do if a read-only handle will not close.
                }
            }
        }
        zip.closeEntry();
        done[0]++;
        if (progress != null) {
            progress.onFile(done[0], total);
        }
    }

    private static int countFiles(final File dir) {
        final File[] kids = dir.listFiles();
        if (kids == null) {
            return 0;
        }
        int n = 0;
        for (final File k : kids) {
            n += k.isDirectory() ? countFiles(k) : 1;
        }
        return n;
    }

    /** Remove earlier backups from a working folder, so the cache does not grow without end. */
    public static void clearOld(final File dir) {
        final File[] kids = (dir == null) ? null : dir.listFiles();
        if (kids == null) {
            return;
        }
        for (final File k : kids) {
            if (k.isFile() && k.getName().endsWith(".zip")) {
                //noinspection ResultOfMethodCallIgnored
                k.delete();
            }
        }
    }

    private static void closeQuietly(final ZipOutputStream zip) {
        if (zip != null) {
            try {
                zip.close();
            } catch (final IOException ignore) {
                // Already failing, or already finished; either way there is nothing to add.
            }
        }
    }
}
