package net.pixelpen.journal.model;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pictures kept with the archive.
 *
 * <p>One folder for all of them, {@code Autumn/Images/<year>/}, rather than a {@code _res} beside
 * every note. Per-note folders multiply without limit, and every one of them turns up in every
 * gallery app on the device until it is hidden; a single tree needs one {@code .nomedia} and can
 * actually be browsed and pruned.
 *
 * <p>Notes link to them relatively, and the link lives in the markdown rather than in this app's
 * head. That is the whole point: a year's note opened on a desktop, or converted to HTML, still
 * has its pictures in the right places. The screen's thumbnail strip is read back out of those
 * links, so the file is the record and the gallery is a view of it.
 *
 * <p>Relative links survive because the archive has fixed depth -- a journal note is always three
 * levels below the root, a timeline note two -- so the prefix is a property of where a note lives,
 * not of the particular note.
 */
public final class ArchiveImages {

    /** Folder under the home folder holding every image. */
    public static final String FOLDER = "Images";

    /**
     * Longest edge, in pixels, of a stored image.
     *
     * <p>Originals are 4-5MB and nothing here needs that: these are reference pictures beside a
     * note, and 1024 is still crisp on a desktop screen while costing a fraction of the archive
     * size that has to be backed up and carried around for decades.
     */
    public static final int MAX_EDGE = 1024;

    private static final int JPEG_QUALITY = 88;

    /** Matches a markdown image link, capturing the path. */
    private static final Pattern IMAGE_LINK = Pattern.compile("!\\[[^\\]]*\\]\\(([^)]+)\\)");

    /** A plain markdown link: the same shape, minus the leading bang that makes it an image. */
    private static final Pattern FILE_LINK =
            Pattern.compile("(?<!!)\\[[^\\]]*\\]\\(([^)]+)\\)");

    private ArchiveImages() {
    }

    /** Folder under the home folder holding attachments that are not pictures. */
    public static final String FILES_FOLDER = "Files";

    /**
     * The year an attachment belongs to, taken from where its note lives.
     *
     * <p>Read from the path rather than the clock: a note written tonight about 1973 sits in a
     * 1973 folder, and its pictures belong with it. Falls back to this year only when the note is
     * somewhere with no year above it at all.
     */
    public static int yearForNote(final File note, final File home) {
        File walk = (note == null) ? null : note.getParentFile();
        final String homePath = (home == null) ? null : home.getAbsolutePath();
        while (walk != null && !walk.getAbsolutePath().equals(homePath)) {
            if (walk.getName().matches("\\d{4}")) {
                return Integer.parseInt(walk.getName());
            }
            walk = walk.getParentFile();
        }
        return java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
    }

    /**
     * Where an attachment for this note should be copied to.
     *
     * <p>Pictures and everything else are kept apart, because "Images" holding a PDF is a folder
     * whose name lies. Both are one tree per kind, under the home folder, rather than a folder
     * beside every note.
     */
    public static File attachmentFolder(final File home, final File note, final boolean isImage)
            throws IOException {
        if (isImage) {
            return yearFolder(home, yearForNote(note, home));
        }
        final File root = new File(home, FILES_FOLDER);
        final File dir = new File(root, String.valueOf(yearForNote(note, home)));
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        // Attachments here can still be audio or video, which music players and galleries index
        // just as eagerly as pictures. Hide the whole tree the same way.
        final File noMedia = new File(root, ".nomedia");
        if (!noMedia.exists()) {
            try {
                noMedia.createNewFile();
            } catch (final IOException ignored) {
                // Clutter in a gallery is not worth failing an attachment over.
            }
        }
        return dir;
    }

    /** True if this name is a picture, by extension. */
    public static boolean isImageName(final String name) {
        return name != null && name.matches("(?i).+\\.(jpg|jpeg|png|webp|gif|bmp)");
    }

    /** {@code Autumn/Images}, whether or not it exists. */
    public static File folderIn(final File home) {
        return (home == null) ? null : new File(home, FOLDER);
    }

    /**
     * The folder for a given year, created along with the {@code .nomedia} that keeps the whole
     * tree out of every gallery app on the device.
     */
    public static File yearFolder(final File home, final int year) throws IOException {
        final File root = folderIn(home);
        if (root == null) {
            throw new IOException("No home folder");
        }
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("Cannot create " + root);
        }
        // One marker at the top covers everything beneath it, which is the saving over a _res
        // folder per note each needing its own.
        final File nomedia = new File(root, ".nomedia");
        if (!nomedia.exists()) {
            //noinspection ResultOfMethodCallIgnored
            nomedia.createNewFile();
        }
        final File dir = new File(root, String.valueOf(year));
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        return dir;
    }

    /**
     * Copy an image into the archive, scaled down, and return the file written.
     *
     * <p>The name carries the date and a trace of the original, so a file picked out of the dump
     * says something about itself. A flat pile is the one thing a per-note folder gave for free --
     * context -- and a timestamped name is how it is bought back.
     */
    public static File importImage(final ContentResolver resolver, final Uri source,
                                   final File home, final int year, final String originalName)
            throws IOException {
        final File dir = yearFolder(home, year);
        final Bitmap bitmap = decodeScaled(resolver, source);
        if (bitmap == null) {
            throw new IOException("Not an image this device can read");
        }
        final File dest = uniqueFile(dir, originalName);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(dest);
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                throw new IOException("Could not write " + dest.getName());
            }
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (final IOException ignore) {
                    // Nothing useful to do; the write either landed or it did not.
                }
            }
            bitmap.recycle();
        }
        return dest;
    }

    /**
     * Read the image at no more than {@link #MAX_EDGE} on its longest side.
     *
     * <p>Measured first, then decoded with a sample size, so a 12-megapixel photo is never fully
     * loaded into memory just to be thrown away. Downscaling after a full decode is how image
     * handling runs a device out of memory.
     */
    private static Bitmap decodeScaled(final ContentResolver resolver, final Uri source)
            throws IOException {
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = resolver.openInputStream(source);
        if (in == null) {
            throw new IOException("Cannot read the chosen image");
        }
        try {
            BitmapFactory.decodeStream(in, null, bounds);
        } finally {
            closeQuietly(in);
        }

        int sample = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (longest / sample > MAX_EDGE * 2) {
            sample *= 2;
        }

        final BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        in = resolver.openInputStream(source);
        if (in == null) {
            throw new IOException("Cannot read the chosen image");
        }
        final Bitmap decoded;
        try {
            decoded = BitmapFactory.decodeStream(in, null, opts);
        } finally {
            closeQuietly(in);
        }
        if (decoded == null) {
            return null;
        }

        // Sampling only halves, so the result is still up to twice the target; this brings it to
        // the exact edge.
        longest = Math.max(decoded.getWidth(), decoded.getHeight());
        if (longest <= MAX_EDGE) {
            return decoded;
        }
        final float ratio = (float) MAX_EDGE / longest;
        final Bitmap scaled = Bitmap.createScaledBitmap(decoded,
                Math.max(1, Math.round(decoded.getWidth() * ratio)),
                Math.max(1, Math.round(decoded.getHeight() * ratio)), true);
        if (scaled != decoded) {
            decoded.recycle();
        }
        return scaled;
    }

    private static File uniqueFile(final File dir, final String originalName) {
        final String stamp = new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.ENGLISH)
                .format(new Date());
        String slug = (originalName == null) ? "" : originalName;
        final int dot = slug.lastIndexOf('.');
        if (dot > 0) {
            slug = slug.substring(0, dot);
        }
        slug = slug.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-+", "-");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40);
        }
        final String base = stamp + (slug.isEmpty() ? "" : "-" + slug);

        File candidate = new File(dir, base + ".jpg");
        int n = 2;
        while (candidate.exists()) {
            candidate = new File(dir, base + "-" + n + ".jpg");
            n++;
        }
        return candidate;
    }

    /**
     * The relative path from a note to a stored image.
     *
     * <p>Worked out by walking up from the note until the home folder is reached, rather than
     * assuming a depth. The two depths in this archive are known, but a path computed from the
     * actual files cannot be wrong, and will still be right if a third ever appears.
     */
    public static String relativeLink(final File noteFile, final File imageFile, final File home) {
        final File noteDir = (noteFile == null) ? null : noteFile.getParentFile();
        if (noteDir == null || home == null || imageFile == null) {
            return imageFile == null ? "" : imageFile.getName();
        }
        final String homePath = home.getAbsolutePath();
        final StringBuilder up = new StringBuilder();
        File walk = noteDir;
        while (walk != null && !walk.getAbsolutePath().equals(homePath)) {
            up.append("../");
            walk = walk.getParentFile();
        }
        if (walk == null) {
            // The note is not under the home folder; an absolute path is wrong in markdown but at
            // least points somewhere real.
            return imageFile.getAbsolutePath();
        }
        final String imgPath = imageFile.getAbsolutePath();
        if (!imgPath.startsWith(homePath + "/")) {
            return imgPath;
        }
        return up + imgPath.substring(homePath.length() + 1);
    }

    /** A markdown image link, with the file's own name as the alt text. */
    public static String markdownLink(final String relativePath, final String alt) {
        final String label = (alt == null) ? "" : alt.replace("]", "").replace("[", "");
        return "![" + label + "](" + relativePath + ")";
    }

    /**
     * Every image a note links to, resolved against where the note lives.
     *
     * <p>This is what the thumbnail strip is built from. The markdown is the record; nothing is
     * listed here that the file does not say.
     */
    public static List<File> linkedImages(final File noteFile, final CharSequence text) {
        final List<File> out = new ArrayList<>();
        final File noteDir = (noteFile == null) ? null : noteFile.getParentFile();
        if (noteDir == null || text == null) {
            return out;
        }
        return resolve(IMAGE_LINK, noteDir, text, out);
    }

    /**
     * Every non-image file a note links to, resolved against where the note lives.
     *
     * <p>Counted from the links rather than by listing a folder. Attachments share one tree per
     * year across the whole archive, so the folder cannot say which of its files belong to this
     * note; the note itself can.
     */
    public static List<File> linkedFiles(final File noteFile, final CharSequence text) {
        final List<File> out = new ArrayList<>();
        final File noteDir = (noteFile == null) ? null : noteFile.getParentFile();
        if (noteDir == null || text == null) {
            return out;
        }
        return resolve(FILE_LINK, noteDir, text, out);
    }

    private static List<File> resolve(final Pattern pattern, final File noteDir,
                                      final CharSequence text, final List<File> out) {
        final Matcher m = pattern.matcher(text);
        while (m.find()) {
            final String path = m.group(1).trim();
            if (path.isEmpty() || path.contains("://")) {
                // Remote targets are somebody else's problem and are not in the archive.
                continue;
            }
            final File f = path.startsWith("/") ? new File(path) : new File(noteDir, path);
            if (f.isFile() && !out.contains(f)) {
                out.add(f);
            }
        }
        return out;
    }

    private static void closeQuietly(final InputStream in) {
        try {
            in.close();
        } catch (final IOException ignore) {
            // Nothing useful to do if a read-only handle will not close.
        }
    }
}
