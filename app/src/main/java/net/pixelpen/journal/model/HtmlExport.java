/*#######################################################
 *
 *   Autumn - journal, archive, timeline
 *   Maintained 2026 by PIXEL-PEN
 *   License: Apache 2.0
 *
#########################################################*/
package net.pixelpen.journal.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Exports the timeline as one self-contained HTML file.
 *
 * <p>Every picture is embedded as a data URI rather than linked. The archive's own links are
 * relative and correct from each note's depth on disk, which is exactly why they break the moment
 * the HTML leaves that folder: a file in Downloads, or attached to mail, has nothing above it to
 * walk up to. Embedding costs about a third again in size and buys a file that opens anywhere,
 * on any machine, with nothing kept alongside it.
 *
 * <p>Pictures are re-encoded on the way in at {@link #EMBED_EDGE}. Insertion already downscales
 * to 1024, so this normally changes nothing; it is here for pictures that arrived some other way
 * and would otherwise put a 5MB original into the page.
 */
public final class HtmlExport {

    /** Longest edge, in pixels, of a picture embedded in an export. */
    private static final int EMBED_EDGE = 1200;

    /** JPEG quality for embedded pictures. */
    private static final int EMBED_QUALITY = 82;

    private HtmlExport() {
    }

    /**
     * Where a picture goes when it is written into a page, and what the page then says about it.
     *
     * <p>The single-file export embeds the bytes and answers with a {@code data:} URI. The
     * whole-archive export copies the file beside the page and answers with its relative path.
     * Returns null for a picture that cannot be used; the page simply leaves it out.
     */
    interface ImageSink {
        String src(File img) throws IOException;
    }

    /** The single-file sink: the picture itself, as a {@code data:} URI. */
    private static final ImageSink EMBED = HtmlExport::dataUri;

    /**
     * Writes the whole timeline to a self-contained HTML file in the cache and returns it.
     *
     * @param context used for the cache folder only
     * @param home    the Autumn home folder
     * @return the written file, or null when there is no timeline to write
     */
    public static File exportTimeline(final Context context, final File home) throws IOException {
        final File timelineDir = Timeline.folderIn(home);
        if (timelineDir == null || !timelineDir.isDirectory()) {
            return null;
        }
        final Timeline timeline = Timeline.read(timelineDir);
        if (timeline == null || timeline.years.isEmpty()) {
            return null;
        }

        final File outDir = new File(context.getCacheDir(), "exports");
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IOException("Cannot create " + outDir);
        }
        // These pages carry every picture in the timeline and run to megabytes. The share sheet
        // only needs the newest one, so an earlier export is dead weight in the cache.
        final File[] stale = outDir.listFiles();
        if (stale != null) {
            for (final File f : stale) {
                if (f.isFile() && f.getName().endsWith(".html")) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }

        final String stamp = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(new Date());
        final File out = new File(outDir, "Autumn-Timeline-" + stamp + ".html");

        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8);
            writeTimeline(w, timeline, home);
            w.flush();
        } catch (final IOException | RuntimeException e) {
            // A half-written page is worse than none: it opens, looks plausible, and is missing
            // the end of someone's life. Leave nothing behind to share by accident.
            closeQuietly(w);
            w = null;
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw (e instanceof IOException) ? (IOException) e : new IOException(e);
        } finally {
            closeQuietly(w);
        }
        return out;
    }

    // ------------------------------------------------------------------ page

    private static void writeTimeline(final Writer w, final Timeline timeline, final File home)
            throws IOException {
        int yearsWritten = 0;
        for (final Timeline.Year y : timeline.years) {
            if (y.hasNotes() || !isBlank(y.highlight)) {
                yearsWritten++;
            }
        }
        final String when = new SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH).format(new Date());

        w.write("<!DOCTYPE html>\n<html lang='en'>\n<head>\n");
        w.write("<meta charset='utf-8'>\n");
        w.write("<meta name='viewport' content='width=device-width, initial-scale=1'>\n");
        w.write("<title>Life Timeline</title>\n<style>\n");
        w.write(CSS);
        w.write("\n</style>\n</head>\n<body>\n");

        w.write("<header><h1>Life Timeline</h1><p class='meta'>");
        w.write(esc(yearsWritten + (yearsWritten == 1 ? " year" : " years")));
        w.write(" &middot; exported ");
        w.write(esc(when));
        w.write("</p></header>\n<main>\n");

        writeDecades(w, timeline, EMBED);

        w.write("</main>\n");
        w.write("<div id='lb' onclick='this.classList.remove(\"on\")'><img id='lbi' alt=''></div>\n");
        w.write("<script>\n");
        w.write(JS);
        w.write("\n</script>\n</body>\n</html>\n");
    }

    /** True when a year has something to show: a bullet or a highlight. */
    static boolean yearHasContent(final Timeline.Year y) {
        return y.hasNotes() || !isBlank(y.highlight);
    }

    /** The timeline's years, grouped under decade headings. Shared by both exports. */
    static void writeDecades(final Writer w, final Timeline timeline, final ImageSink images)
            throws IOException {
        int openDecade = Integer.MIN_VALUE;
        for (final Timeline.Year y : timeline.years) {
            if (!yearHasContent(y)) {
                continue;
            }
            if (y.decade() != openDecade) {
                if (openDecade != Integer.MIN_VALUE) {
                    w.write("</section>\n");
                }
                openDecade = y.decade();
                w.write("<section class='decade'><h2>");
                w.write(esc(openDecade + "s"));
                w.write("</h2>\n");
            }
            writeYear(w, y, images);
        }
        if (openDecade != Integer.MIN_VALUE) {
            w.write("</section>\n");
        }
    }

    private static void writeYear(final Writer w, final Timeline.Year y, final ImageSink images)
            throws IOException {
        w.write("<article class='year'>\n<div class='yhead'><span class='yr'>");
        w.write(esc(String.valueOf(y.year)));
        w.write("</span>");
        if (!isBlank(y.highlight)) {
            w.write("<span class='pill'>");
            w.write(esc(y.highlight.trim()));
            w.write("</span>");
        }
        w.write("</div>\n");

        if (!y.bullets.isEmpty()) {
            w.write("<ul class='bul'>\n");
            int shownMonth = Integer.MIN_VALUE;
            for (final Timeline.Bullet b : y.bullets) {
                if (isBlank(b.text)) {
                    continue;
                }
                w.write("<li><span class='mo'>");
                // The month is printed once per run, the way the note's own column reads, so a
                // year of January entries does not repeat the word fourteen times.
                if (b.month >= 0 && b.month < Timeline.MONTH_SHORT.length && b.month != shownMonth) {
                    w.write(esc(Timeline.MONTH_SHORT[b.month]));
                    shownMonth = b.month;
                }
                w.write("</span><span class='tx'>");
                w.write(esc(b.text.trim()));
                w.write("</span></li>\n");
            }
            w.write("</ul>\n");
        }

        writeImages(w, y, images);
        w.write("</article>\n");
    }

    private static void writeImages(final Writer w, final Timeline.Year y, final ImageSink images)
            throws IOException {
        if (y.images.isEmpty()) {
            return;
        }
        final List<String> embedded = new ArrayList<>();
        for (final File img : y.images) {
            final String uri = images.src(img);
            if (uri != null) {
                embedded.add(uri);
            }
        }
        if (embedded.isEmpty()) {
            return;
        }
        w.write("<div class='shots'>\n");
        for (final String uri : embedded) {
            // Thumbnail and full view are the same bytes: the page is already carrying them, and
            // a second copy at a larger size would double the file for nothing.
            w.write("<img loading='lazy' alt='' onclick='big(this)' src='");
            w.write(esc(uri));
            w.write("'>\n");
        }
        w.write("</div>\n");
    }

    // ------------------------------------------------------------- embedding

    /** A picture as a {@code data:} URI, or null when it cannot be read as an image. */
    private static String dataUri(final File img) {
        if (img == null || !img.isFile()) {
            return null;
        }
        Bitmap bmp = null;
        try {
            final BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(img.getAbsolutePath(), probe);
            final int longest = Math.max(probe.outWidth, probe.outHeight);
            if (longest <= 0) {
                return null;
            }
            final BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, Integer.highestOneBit(longest / EMBED_EDGE));
            bmp = BitmapFactory.decodeFile(img.getAbsolutePath(), opts);
            if (bmp == null) {
                return null;
            }
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, EMBED_QUALITY, buf);
            return "data:image/jpeg;base64," + Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP);
        } catch (final OutOfMemoryError | RuntimeException e) {
            // One unreadable or enormous picture should not cost the whole export.
            return null;
        } finally {
            if (bmp != null) {
                bmp.recycle();
            }
        }
    }

    // ----------------------------------------------------------------- utils

    static boolean isBlank(final String s) {
        return s == null || s.trim().isEmpty();
    }

    private static void closeQuietly(final Writer w) {
        if (w != null) {
            try {
                w.close();
            } catch (final IOException ignored) {
                // Nothing useful to do while already handling a failure.
            }
        }
    }

    /** Escapes text for HTML. Entries are the user's own words and may contain anything. */
    static String esc(final String s) {
        if (s == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&#39;");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    // Single quotes throughout the emitted markup, so none of this needs escaping in Java.
    static final String CSS =
            ":root{--ink:#22312f;--dim:#6d7f7c;--teal:#2e6f68;--pill:#f2d26b;--line:#dde5e3;"
                    + "--bg:#f7f9f8;--card:#ffffff}\n"
                    + "*{box-sizing:border-box}\n"
                    + "body{margin:0;background:var(--bg);color:var(--ink);"
                    + "font:16px/1.55 -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif}\n"
                    + "header{background:var(--teal);color:#fff;padding:28px 24px}\n"
                    + "h1{margin:0;font-size:26px;font-weight:600;letter-spacing:.2px}\n"
                    + ".meta{margin:6px 0 0;opacity:.85;font-size:13px}\n"
                    + "main{max-width:820px;margin:0 auto;padding:24px 16px 64px}\n"
                    + ".decade{margin:0 0 28px}\n"
                    + ".decade>h2{position:sticky;top:0;z-index:2;margin:0 0 12px;padding:10px 2px;"
                    + "background:var(--bg);font-size:13px;font-weight:700;letter-spacing:1.6px;"
                    + "text-transform:uppercase;color:var(--dim);border-bottom:1px solid var(--line)}\n"
                    + ".year{background:var(--card);border:1px solid var(--line);border-radius:10px;"
                    + "padding:16px 18px;margin:0 0 12px}\n"
                    + ".yhead{display:flex;align-items:center;gap:12px;flex-wrap:wrap}\n"
                    + ".yr{font-size:22px;font-weight:700;color:var(--teal);letter-spacing:.5px}\n"
                    + ".pill{background:var(--pill);color:#4a3b05;border-radius:999px;"
                    + "padding:4px 12px;font-size:13px;font-weight:600}\n"
                    + ".bul{list-style:none;margin:14px 0 0;padding:0}\n"
                    + ".bul li{display:flex;gap:14px;padding:5px 0;align-items:baseline}\n"
                    + ".mo{flex:0 0 34px;font-size:12px;font-weight:600;color:var(--dim);"
                    + "text-align:right;padding-top:2px}\n"
                    + ".tx{flex:1 1 auto}\n"
                    + ".tx::before{content:'\\2022';color:var(--dim);margin-right:8px}\n"
                    + ".shots{display:flex;flex-wrap:wrap;gap:8px;margin:14px 0 0}\n"
                    + ".shots img{width:108px;height:108px;object-fit:cover;border-radius:6px;"
                    + "cursor:zoom-in;background:var(--line)}\n"
                    + "#lb{display:none;position:fixed;inset:0;z-index:9;background:rgba(14,22,21,.94);"
                    + "align-items:center;justify-content:center;cursor:zoom-out;padding:24px}\n"
                    + "#lb.on{display:flex}\n"
                    + "#lb img{max-width:100%;max-height:100%;border-radius:4px}\n"
                    + "@media print{body{background:#fff}header{background:#fff;color:var(--ink);"
                    + "border-bottom:2px solid var(--teal)}.decade>h2{position:static}"
                    + ".year{break-inside:avoid;border-color:#ccc}#lb{display:none!important}}\n";

    static final String JS =
            "function big(el){var b=document.getElementById('lb');"
                    + "document.getElementById('lbi').src=el.src;b.classList.add('on');}\n"
                    + "document.addEventListener('keydown',function(e){"
                    + "if(e.key==='Escape'){document.getElementById('lb').classList.remove('on');}});\n";
}
