/*#######################################################
 *
 *   Autumn - journal, archive, timeline
 *   Maintained 2026 by PIXEL-PEN
 *   License: Apache 2.0
 *
#########################################################*/
package net.pixelpen.journal.model;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Pictures for the archive export, reduced to {@link #EDGE} on the longest side.
 *
 * <p>800px reads well in a desktop page and opened full screen, and keeps a typical photo near
 * 70-100KB. Insertion stores pictures at 1024 and imported archives may hold full-size originals
 * at several megabytes each; copied as they are, a few hundred of those made an export of over
 * 300MB.
 */
public final class ExportPictures implements ArchiveHtmlExport.Pictures {

    /** Longest edge, in pixels, of a picture in the export. */
    static final int EDGE = 800;

    /** JPEG quality for exported pictures. */
    static final int QUALITY = 75;

    @Override
    public boolean fits(final File src) {
        final int longest = longestEdge(src);
        return longest > 0 && longest <= EDGE;
    }

    @Override
    public boolean writeJpeg(final File src, final File dest) throws IOException {
        final int longest = longestEdge(src);
        if (longest <= 0) {
            return false;
        }
        Bitmap decoded = null;
        Bitmap scaled = null;
        Bitmap flat = null;
        OutputStream out = null;
        try {
            // Decode at the largest power-of-two reduction that stays at or above EDGE, then
            // scale exactly. Stopping at the power of two alone can leave a picture at nearly
            // twice the size, and four times the bytes.
            final BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, Integer.highestOneBit(longest / EDGE));
            decoded = BitmapFactory.decodeFile(src.getAbsolutePath(), opts);
            if (decoded == null) {
                return false;
            }
            final int w = decoded.getWidth();
            final int h = decoded.getHeight();
            final float k = Math.min(1f, (float) EDGE / Math.max(w, h));
            final int tw = Math.max(1, Math.round(w * k));
            final int th = Math.max(1, Math.round(h * k));
            scaled = (tw == w && th == h) ? decoded : Bitmap.createScaledBitmap(decoded, tw, th, true);

            // JPEG has no transparency; a transparent PNG would come out black where it was
            // clear. Laid on white first, it looks the way it did on the page.
            Bitmap toWrite = scaled;
            if (scaled.hasAlpha()) {
                flat = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565);
                final Canvas canvas = new Canvas(flat);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(scaled, 0f, 0f, null);
                toWrite = flat;
            }

            out = new FileOutputStream(dest);
            return toWrite.compress(Bitmap.CompressFormat.JPEG, QUALITY, out);
        } catch (final OutOfMemoryError | RuntimeException e) {
            // One unreadable or enormous picture should not cost the whole export.
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (final IOException ignored) {
                    // Nothing useful to do; the caller deletes a failed file.
                }
            }
            if (flat != null) {
                flat.recycle();
            }
            if (scaled != null && scaled != decoded) {
                scaled.recycle();
            }
            if (decoded != null) {
                decoded.recycle();
            }
        }
    }

    private static int longestEdge(final File src) {
        if (src == null || !src.isFile()) {
            return 0;
        }
        final BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getAbsolutePath(), probe);
        return Math.max(probe.outWidth, probe.outHeight);
    }
}
