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
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads thumbnails off the main thread into recycled image views.
 *
 * <p>A deliberately small thing rather than an image library: the archive holds its own pictures
 * on local disk at a known size, so there is nothing to fetch, retry or transform. What is needed
 * is a decode that does not happen on the main thread and a cache so scrolling back up is free.
 *
 * <p>Recycling is handled by tagging the view with the path it was asked for. A view scrolled away
 * and reused is already carrying a different tag by the time the old decode finishes, so the stale
 * bitmap is dropped instead of flashing into the wrong row.
 */
public final class ThumbLoader {

    /** Roughly 40 thumbnails at 220px square, which covers a long scroll. */
    private static final int CACHE_BYTES = 8 * 1024 * 1024;

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(final String key, final Bitmap value) {
            return value.getByteCount();
        }
    };

    // One thread: decoding is CPU and disk bound, and a queue keeps the order the rows appear in.
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ThumbLoader() {
    }

    /** Key on path, size and mtime, so an edited or replaced picture is not served stale. */
    private static String keyFor(final File f, final int edge) {
        return f.getAbsolutePath() + "|" + edge + "|" + f.length() + "|" + f.lastModified();
    }

    /**
     * Shows {@code file} in {@code view}, decoding it first if it is not already cached.
     *
     * @param edge the longest edge wanted, in pixels
     */
    public static void load(final ImageView view, final File file, final int edge) {
        if (view == null) {
            return;
        }
        if (file == null || !file.isFile()) {
            view.setTag(null);
            view.setImageDrawable(null);
            return;
        }
        final String key = keyFor(file, edge);
        view.setTag(key);

        final Bitmap cached = CACHE.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        // Clear first: this view may still be showing whatever the last row put in it.
        view.setImageDrawable(null);

        final WeakReference<ImageView> ref = new WeakReference<>(view);
        POOL.execute(() -> {
            final Bitmap bmp = decode(file, edge);
            if (bmp == null) {
                return;
            }
            CACHE.put(key, bmp);
            MAIN.post(() -> {
                final ImageView target = ref.get();
                // Only if this view is still the one that asked for this picture.
                if (target != null && key.equals(target.getTag())) {
                    target.setImageBitmap(bmp);
                }
            });
        });
    }

    /** Decodes at the smallest power-of-two sample that still covers {@code edge}. */
    private static Bitmap decode(final File file, final int edge) {
        try {
            final BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), probe);
            final int longest = Math.max(probe.outWidth, probe.outHeight);
            if (longest <= 0) {
                return null;
            }
            final BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = (edge <= 0) ? 1 : Math.max(1, Integer.highestOneBit(longest / edge));
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (final OutOfMemoryError | RuntimeException e) {
            // A picture that will not decode shows as an empty tile rather than taking the screen
            // down with it.
            return null;
        }
    }
}
