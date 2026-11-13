/*#######################################################
 *
 *   Autumn - journal, archive, timeline
 *   Maintained 2026 by PIXEL-PEN
 *   License: Apache 2.0
 *
#########################################################*/
package net.pixelpen.journal.model;

import net.pixelpen.journal.util.NoteTimestamp;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exports the whole archive as a small website: a folder holding {@code index.html} and, beside
 * it, the pictures and attachments the notes use, zipped for the share sheet.
 *
 * <p>A folder rather than one file, because the pictures are the weight. Each is written to disk
 * once and the page points at it, so memory stays flat however large the archive grows and the
 * page opens at once. Embedding every picture in one file, as the timeline export does, would
 * make the whole archive one multi-hundred-megabyte string of base64.
 *
 * <p>Links are rewritten on the way through. The archive's own links are relative to each note's
 * depth on disk ({@code ../../Images/2026/a.jpg}), which is right there and wrong anywhere else,
 * so each linked file is copied to {@code Images/}, {@code Files/} or {@code Pages/} and the link
 * re-pointed at it from the page. Nothing in the archive is modified.
 *
 * <p>The page is for reading on a desktop: a contents column of years and months, a category
 * filter, a text filter, and the notes in date order, oldest first, the way a diary reads. The
 * app shows newest first because capture happens at the top; reading back is the other direction.
 *
 * <p>No Android types and no markdown library in here; rendering arrives through {@link Markdown}.
 * That keeps the walking, link rewriting and copying runnable on a plain JDK.
 */
public final class ArchiveHtmlExport {

    /** Turns one note's markdown into an HTML fragment. */
    public interface Markdown {
        String toHtml(String markdown);
    }

    /** Reports progress so a long export can say what it is doing. */
    public interface Progress {
        void onNote(int done, int total);
    }

    /**
     * Reduces pictures for the export. Android decoding lives behind this so the rest of the class
     * still runs on a plain JDK.
     */
    public interface Pictures {
        /** True when the picture's longest edge is already within the export size. */
        boolean fits(File src);

        /**
         * Writes a reduced JPEG of {@code src} to {@code dest}. False when the picture cannot be
         * decoded, in which case nothing usable was written.
         */
        boolean writeJpeg(File src, File dest) throws IOException;
    }

    /**
     * A picture already within the size and at most this many bytes goes in unchanged, which
     * keeps small PNGs and GIFs as they are. Anything bigger is re-encoded.
     */
    private static final long SMALL_PICTURE = 200L * 1024L;

    /**
     * Attachments and saved pages above this are left out and named instead. The export is for
     * reading; a video or a scanned PDF would outweigh every note and picture together.
     */
    private static final long MAX_ATTACHMENT = 2L * 1024L * 1024L;

    private static final Pattern NUM_PREFIX = Pattern.compile("^\\d{1,2}[_-]");
    private static final Pattern EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{1,8}$");
    private static final Pattern YEAR_DIR = Pattern.compile("\\d{4}");

    /** A markdown link or image: group 1 the bang, 2 the label, 3 the target. */
    private static final Pattern LINK = Pattern.compile("(!?)\\[([^\\]]*)\\]\\(([^)]+)\\)");

    private static final Set<String> NOTE_EXTS = new HashSet<>();
    private static final Set<String> PAGE_EXTS = new HashSet<>();
    private static final Set<String> IMAGE_EXTS = new HashSet<>();

    static {
        NOTE_EXTS.add("md");
        NOTE_EXTS.add("markdown");
        NOTE_EXTS.add("txt");
        PAGE_EXTS.add("html");
        PAGE_EXTS.add("htm");
        IMAGE_EXTS.add("jpg");
        IMAGE_EXTS.add("jpeg");
        IMAGE_EXTS.add("png");
        IMAGE_EXTS.add("webp");
        IMAGE_EXTS.add("gif");
        IMAGE_EXTS.add("bmp");
    }

    private ArchiveHtmlExport() {
    }

    // ------------------------------------------------------------------ model

    private static final class Note {
        final File file;
        /** Empty when the note sits directly in its month folder. */
        final String category;
        final String title;
        final long when;
        /** A saved web page, linked rather than rendered. */
        final boolean page;

        Note(final File file, final String category, final String title, final long when,
             final boolean page) {
            this.file = file;
            this.category = category;
            this.title = title;
            this.when = when;
            this.page = page;
        }
    }

    private static final class Month {
        final String name;
        final String id;
        final List<Note> notes = new ArrayList<>();

        Month(final String name, final String id) {
            this.name = name;
            this.id = id;
        }
    }

    private static final class Year {
        final int year;
        final List<Month> months = new ArrayList<>();

        Year(final int year) {
            this.year = year;
        }
    }

    // ------------------------------------------------------------------ entry

    /**
     * Builds the export under {@code workDir} and returns the zip.
     *
     * @param home    the Autumn home folder
     * @param workDir a folder this class owns: anything already in it is deleted first, so an
     *                earlier export does not sit in the cache beside the new one
     * @param md      renders a note's markdown to HTML
     * @return the zip, or null when the archive holds no notes and no timeline
     * @throws IOException if anything fails; nothing is left behind to be shared by accident
     */
    public static File export(final File home, final File workDir, final Markdown md,
                              final Pictures pictures, final Progress progress)
            throws IOException {
        if (home == null || !home.isDirectory() || workDir == null) {
            return null;
        }
        final List<Year> years = scan(home);
        final Timeline timeline = Timeline.read(Timeline.folderIn(home));
        boolean hasTimeline = false;
        for (final Timeline.Year y : timeline.years) {
            if (HtmlExport.yearHasContent(y)) {
                hasTimeline = true;
                break;
            }
        }
        int total = 0;
        for (final Year y : years) {
            for (final Month m : y.months) {
                total += m.notes.size();
            }
        }
        if (total == 0 && !hasTimeline) {
            return null;
        }

        deleteTree(workDir);
        final String stamp = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(new Date());
        final String folderName = "Autumn-web-" + stamp;
        final File site = new File(workDir, folderName);
        final File zip = new File(workDir, folderName + ".zip");
        if (!site.mkdirs()) {
            throw new IOException("Cannot create " + site);
        }

        try {
            final Assets assets = new Assets(home, site, pictures);
            Writer w = null;
            try {
                w = new OutputStreamWriter(
                        new FileOutputStream(new File(site, "index.html")), StandardCharsets.UTF_8);
                writePage(w, home, years, timeline, hasTimeline, total, md, assets, progress);
                w.flush();
            } finally {
                closeQuietly(w);
            }
            ArchiveBackup.create(site, zip, null);
            // Only the zip is handed on. The loose folder is the same bytes again.
            deleteTree(site);
            return zip;
        } catch (final IOException | RuntimeException e) {
            deleteTree(site);
            //noinspection ResultOfMethodCallIgnored
            zip.delete();
            throw (e instanceof IOException) ? (IOException) e : new IOException(e);
        }
    }

    // ------------------------------------------------------------------- scan

    /** Years ascending, months in calendar order, notes oldest first. Empty ones are dropped. */
    private static List<Year> scan(final File home) {
        final List<Year> out = new ArrayList<>();
        final File[] kids = home.listFiles();
        if (kids == null) {
            return out;
        }
        sortByName(kids);
        for (final File yf : kids) {
            if (!yf.isDirectory() || !YEAR_DIR.matcher(yf.getName()).matches()) {
                continue;
            }
            final Year year = new Year(Integer.parseInt(yf.getName()));
            final File[] months = yf.listFiles();
            if (months == null) {
                continue;
            }
            sortByMonth(months);
            for (final File mf : months) {
                if (!mf.isDirectory() || skipName(mf.getName()) || hasSkipMarker(mf)) {
                    continue;
                }
                final String shown = NUM_PREFIX.matcher(mf.getName()).replaceFirst("")
                        .replace('_', ' ');
                final Month month = new Month(shown, "y" + year.year + "-" + year.months.size());
                collect(mf, "", month.notes);
                if (month.notes.isEmpty()) {
                    continue;
                }
                Collections.sort(month.notes, (a, b) -> {
                    final int byTime = Long.compare(a.when, b.when);
                    return byTime != 0 ? byTime : a.file.getName().compareToIgnoreCase(b.file.getName());
                });
                year.months.add(month);
            }
            if (!year.months.isEmpty()) {
                out.add(year);
            }
        }
        Collections.sort(out, (a, b) -> Integer.compare(a.year, b.year));
        return out;
    }

    private static void collect(final File dir, final String category, final List<Note> out) {
        final File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (final File f : kids) {
            final String name = f.getName();
            if (skipName(name)) {
                continue;
            }
            if (f.isDirectory()) {
                if (!hasSkipMarker(f)) {
                    collect(f, category.isEmpty() ? name : category + " / " + name, out);
                }
            } else if (f.isFile()) {
                final String ext = extensionOf(name);
                final boolean page = PAGE_EXTS.contains(ext);
                if (!page && !NOTE_EXTS.contains(ext)) {
                    continue;
                }
                long when = f.lastModified();
                if (!page) {
                    final Long stamped = NoteTimestamp.parseAboutDate(firstLine(f));
                    if (stamped != null) {
                        when = stamped;
                    }
                }
                out.add(new Note(f, category, EXTENSION.matcher(name).replaceFirst(""), when, page));
            }
        }
    }

    private static boolean skipName(final String name) {
        return name.startsWith(".") || "_res".equals(name);
    }

    private static boolean hasSkipMarker(final File dir) {
        return new File(dir, ".skip-scan").exists();
    }

    private static String extensionOf(final String name) {
        final int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void sortByName(final File[] files) {
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
    }

    /** Leading number where there is one, so months read January..December, not April first. */
    private static void sortByMonth(final File[] files) {
        java.util.Arrays.sort(files, (a, b) -> {
            final int na = leadingNumber(a.getName());
            final int nb = leadingNumber(b.getName());
            return na != nb ? Integer.compare(na, nb) : a.getName().compareToIgnoreCase(b.getName());
        });
    }

    private static int leadingNumber(final String name) {
        final Matcher m = NUM_PREFIX.matcher(name);
        if (!m.find()) {
            return Integer.MAX_VALUE;
        }
        return Integer.parseInt(name.substring(0, m.end() - 1));
    }

    // ------------------------------------------------------------------- page

    private static void writePage(final Writer w, final File home, final List<Year> years,
                                  final Timeline timeline, final boolean hasTimeline,
                                  final int total, final Markdown md, final Assets assets,
                                  final Progress progress) throws IOException {
        final String when = new SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH).format(new Date());
        final String name = home.getName().isEmpty() ? "Autumn" : home.getName();

        final Set<String> categories = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (final Year y : years) {
            for (final Month m : y.months) {
                for (final Note n : m.notes) {
                    if (!n.category.isEmpty()) {
                        categories.add(n.category);
                    }
                }
            }
        }

        w.write("<!DOCTYPE html>\n<html lang='en'>\n<head>\n<meta charset='utf-8'>\n");
        w.write("<meta name='viewport' content='width=device-width, initial-scale=1'>\n");
        w.write("<title>");
        w.write(HtmlExport.esc(name));
        w.write("</title>\n<style>\n");
        w.write(HtmlExport.CSS);
        w.write("\n");
        w.write(CSS);
        w.write("\n</style>\n</head>\n<body>\n");

        w.write("<header><h1>");
        w.write(HtmlExport.esc(name));
        w.write("</h1><p class='meta'>");
        w.write(total + (total == 1 ? " note" : " notes"));
        w.write(" &middot; exported ");
        w.write(HtmlExport.esc(when));
        w.write("</p></header>\n<div class='wrap'>\n");

        // ---- contents column
        w.write("<nav id='nav'>\n<input id='q' type='search' placeholder='Search the text' ");
        w.write("aria-label='Search the text'>\n");
        if (!categories.isEmpty()) {
            w.write("<select id='cat' aria-label='Category'><option value=''>All categories</option>");
            for (final String c : categories) {
                w.write("<option value='" + HtmlExport.esc(c) + "'>" + HtmlExport.esc(c) + "</option>");
            }
            w.write("</select>\n");
        }
        if (hasTimeline) {
            w.write("<a class='y' href='#timeline'>Timeline</a>\n");
        }
        for (final Year y : years) {
            w.write("<a class='y' href='#y" + y.year + "'>" + y.year + "</a>\n");
            for (final Month m : y.months) {
                w.write("<a class='m' href='#" + m.id + "'>" + HtmlExport.esc(m.name) + "</a>\n");
            }
        }
        w.write("</nav>\n<div class='content'>\n");

        // ---- timeline
        if (hasTimeline) {
            w.write("<details id='timeline'><summary class='sh'>Timeline</summary>\n");
            HtmlExport.writeDecades(w, timeline, assets);
            w.write("</details>\n");
        }

        // ---- the archive
        int done = 0;
        for (int i = 0; i < years.size(); i++) {
            final Year y = years.get(i);
            // Each year opens and closes on its heading. The newest starts open, so the page
            // does not open on a blank list of years.
            w.write("<details class='ysec' id='y" + y.year + "'"
                    + (i == years.size() - 1 ? " open" : "") + "><summary class='sh'>"
                    + y.year + "</summary>\n");
            for (final Month m : y.months) {
                w.write("<section class='month' id='" + m.id + "'><h3>" + HtmlExport.esc(m.name)
                        + "</h3>\n");
                for (final Note n : m.notes) {
                    writeNote(w, n, md, assets);
                    done++;
                    if (progress != null) {
                        progress.onNote(done, total);
                    }
                }
                w.write("</section>\n");
            }
            w.write("</details>\n");
        }
        if (assets.omitted > 0) {
            w.write("<p class='omit'>" + assets.omitted + (assets.omitted == 1
                    ? " file was" : " files were") + " left out: attachments and saved pages over "
                    + megabytes(MAX_ATTACHMENT) + ", and pictures that could not be read. "
                    + "Each is named where it appeared.</p>\n");
        }

        w.write("</div>\n</div>\n");
        w.write("<div id='lb' onclick='this.classList.remove(\"on\")'><img id='lbi' alt=''></div>\n");
        w.write("<script>\n");
        w.write(HtmlExport.JS);
        w.write("\n");
        w.write(JS);
        w.write("\n</script>\n</body>\n</html>\n");
    }

    private static void writeNote(final Writer w, final Note n, final Markdown md,
                                  final Assets assets) throws IOException {
        w.write("<article class='note' data-cat='" + HtmlExport.esc(n.category) + "'>\n<h4>");
        w.write(HtmlExport.esc(n.title));
        w.write("</h4>\n<div class='by'>");

        if (n.page) {
            writeCategory(w, n);
            final String placed = assets.place(n.file, "Pages");
            if (placed == null) {
                w.write("</div>\n<p class='body'><em>Saved page not included: "
                        + megabytes(n.file.length()) + "</em></p>\n</article>\n");
            } else {
                w.write("</div>\n<p class='body'><a href='");
                w.write(HtmlExport.esc(url(placed)));
                w.write("'>Open the saved page</a></p>\n</article>\n");
            }
            return;
        }

        String text = readText(n.file);
        // No stamp, no date. The file's modified time is when it was last copied or synced, not
        // when the entry happened, and printing it would put a wrong date under a right one.
        String dateLabel = "";
        final int nl = text.indexOf('\n');
        final String first = nl < 0 ? text : text.substring(0, nl);
        if (NoteTimestamp.parseAboutDate(first) != null) {
            // Line 1 is the date the note is about. It is shown in the byline, not repeated in
            // the body.
            dateLabel = first.trim();
            text = nl < 0 ? "" : text.substring(nl + 1);
        }
        if (!dateLabel.isEmpty()) {
            w.write("<span class='date'>" + HtmlExport.esc(dateLabel) + "</span>");
        }
        writeCategory(w, n);
        w.write("</div>\n");

        if (!text.trim().isEmpty()) {
            final String html = md.toHtml(rewriteLinks(text, n.file, assets));
            w.write("<div class='body'>");
            w.write(html);
            w.write("</div>\n");
        }
        w.write("</article>\n");
    }

    private static void writeCategory(final Writer w, final Note n) throws IOException {
        if (!n.category.isEmpty()) {
            w.write("<span class='cat'>" + HtmlExport.esc(n.category) + "</span>");
        }
    }

    // ------------------------------------------------------------------ links

    /**
     * Every local link in the note, re-pointed at the copy beside the page. Remote links, anchors
     * and anything that is not a file on disk are left as written.
     */
    private static String rewriteLinks(final String text, final File note, final Assets assets)
            throws IOException {
        final File noteDir = note.getParentFile();
        final Matcher m = LINK.matcher(text);
        final StringBuffer sb = new StringBuffer(text.length() + 64);
        while (m.find()) {
            final File f = resolveLocal(noteDir, m.group(3).trim());
            String out = m.group(0);
            if (f != null) {
                final boolean image = IMAGE_EXTS.contains(extensionOf(f.getName()));
                final String placed = assets.place(f, image ? "Images" : "Files");
                if (placed != null) {
                    out = m.group(1) + "[" + m.group(2) + "](" + url(placed) + ")";
                } else {
                    // Left out of the export. Say so in the note, where the reader would have
                    // seen it, rather than leave a broken picture or a link to nothing.
                    out = "*(" + (image ? "picture" : "attachment") + " not included: "
                            + f.getName().replace("*", "").replace("_", "\\_") + ", "
                            + megabytes(f.length()) + ")*";
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(out));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** The file a link names, or null when it is remote, an anchor, or not there. */
    private static File resolveLocal(final File noteDir, final String target) {
        if (noteDir == null || target.isEmpty() || target.startsWith("#") || target.startsWith("<")
                || target.contains("://") || target.startsWith("mailto:")
                || target.startsWith("data:")) {
            return null;
        }
        File f = target.startsWith("/") ? new File(target) : new File(noteDir, target);
        if (!f.isFile()) {
            // Links written by other tools are often percent-encoded. "+" is a literal plus in a
            // path, not a space, so it is protected before decoding.
            try {
                final String decoded = URLDecoder.decode(target.replace("+", "%2B"), "UTF-8");
                f = decoded.startsWith("/") ? new File(decoded) : new File(noteDir, decoded);
            } catch (final UnsupportedEncodingException | IllegalArgumentException e) {
                return null;
            }
            if (!f.isFile()) {
                return null;
            }
        }
        return canon(f);
    }

    /** A relative path as a URL: each segment percent-encoded, the slashes kept. */
    private static String url(final String rel) {
        final String[] parts = rel.split("/");
        final StringBuilder sb = new StringBuilder(rel.length() + 16);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            try {
                sb.append(URLEncoder.encode(parts[i], "UTF-8").replace("+", "%20"));
            } catch (final UnsupportedEncodingException e) {
                sb.append(parts[i]);
            }
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------- assets

    /**
     * Copies each linked file beside the page, once, and says where it went.
     *
     * <p>Pictures keep the year folder they have in the archive ({@code Images/2026/a.jpg}).
     * Anything from outside the archive's own folders lands in {@code other/}, where a clash of
     * names is settled with a numeric suffix rather than one file overwriting another.
     */
    private static final class Assets implements HtmlExport.ImageSink {
        private final File home;
        private final File site;
        private final Pictures pictures;
        /** Source to page-relative path; a null value means the file was left out. */
        private final Map<String, String> placed = new HashMap<>();
        private final Set<String> used = new HashSet<>();
        int omitted;

        Assets(final File home, final File site, final Pictures pictures) {
            this.home = canon(home);
            this.site = site;
            this.pictures = pictures;
        }

        @Override
        public String src(final File img) throws IOException {
            if (img == null || !img.isFile()) {
                return null;
            }
            final String placed = place(canon(img), "Images");
            return placed == null ? null : url(placed);
        }

        /**
         * The path of the copy, relative to the page, e.g. {@code Images/2026/a.jpg}, or null
         * when the file is left out of the export.
         */
        String place(final File source, final String bucket) throws IOException {
            final File src = canon(source);
            final String key = bucket + "|" + src.getAbsolutePath();
            if (placed.containsKey(key)) {
                return placed.get(key);
            }
            String rel = "Pages".equals(bucket)
                    ? under(src, home)
                    : under(src, new File(home, bucket));
            if (rel == null) {
                rel = "other/" + src.getName();
            }

            final boolean picture = "Images".equals(bucket);
            final boolean reduce = picture
                    && !(src.length() <= SMALL_PICTURE && pictures.fits(src));
            if (reduce) {
                rel = withExtension(rel, ".jpg");
            } else if (src.length() > MAX_ATTACHMENT) {
                placed.put(key, null);
                omitted++;
                return null;
            }

            final String dest = unique(bucket + "/", rel);
            final File out = new File(site, dest);
            final File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Cannot create " + parent);
            }
            if (reduce) {
                if (!pictures.writeJpeg(src, out)) {
                    // Android cannot read it, so a desktop browser most likely cannot either.
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    used.remove(dest);
                    placed.put(key, null);
                    omitted++;
                    return null;
                }
            } else {
                copy(src, out);
            }
            placed.put(key, dest);
            return dest;
        }

        /** {@code prefix + rel}, with a numeric suffix if that path is already taken. */
        private String unique(final String prefix, final String rel) {
            String dest = prefix + rel;
            final int slash = rel.lastIndexOf('/');
            final int dot = rel.lastIndexOf('.');
            final String stem = dot > slash ? rel.substring(0, dot) : rel;
            final String ext = dot > slash ? rel.substring(dot) : "";
            for (int n = 2; !used.add(dest); n++) {
                dest = prefix + stem + "-" + n + ext;
            }
            return dest;
        }

        private static String under(final File f, final File base) {
            final String prefix = canon(base).getAbsolutePath() + "/";
            final String path = f.getAbsolutePath();
            return path.startsWith(prefix) ? path.substring(prefix.length()) : null;
        }
    }

    private static String withExtension(final String rel, final String ext) {
        final int slash = rel.lastIndexOf('/');
        final int dot = rel.lastIndexOf('.');
        return (dot > slash ? rel.substring(0, dot) : rel) + ext;
    }

    private static String megabytes(final long bytes) {
        if (bytes < 1024L * 1024L) {
            return Math.max(1L, Math.round(bytes / 1024.0)) + " KB";
        }
        return String.format(Locale.ENGLISH, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static void copy(final File from, final File to) throws IOException {
        final File parent = to.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(from);
            out = new FileOutputStream(to);
            final byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
        //noinspection ResultOfMethodCallIgnored
        to.setLastModified(from.lastModified());
    }

    // ------------------------------------------------------------------ utils

    private static String firstLine(final File f) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f),
                    StandardCharsets.UTF_8));
            final String line = br.readLine();
            return line == null ? null : stripBom(line);
        } catch (final IOException e) {
            return null;
        } finally {
            closeQuietly(br);
        }
    }

    private static String readText(final File f) throws IOException {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            final ByteArrayOutputStream buf = new ByteArrayOutputStream(
                    (int) Math.min(Math.max(f.length(), 16L), 1L << 24));
            final byte[] chunk = new byte[16384];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return stripBom(new String(buf.toByteArray(), StandardCharsets.UTF_8))
                    .replace("\r\n", "\n");
        } finally {
            closeQuietly(in);
        }
    }

    private static String stripBom(final String s) {
        return (s.length() > 0 && s.charAt(0) == '﻿') ? s.substring(1) : s;
    }

    private static File canon(final File f) {
        try {
            return f.getCanonicalFile();
        } catch (final IOException e) {
            return f.getAbsoluteFile();
        }
    }

    private static void deleteTree(final File f) {
        if (f == null || !f.exists()) {
            return;
        }
        final File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) {
            for (final File k : kids) {
                deleteTree(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void closeQuietly(final java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (final IOException ignored) {
                // Nothing useful to do while already finishing or failing.
            }
        }
    }

    // ---------------------------------------------------------- page assets

    // Single quotes throughout the emitted markup, so none of this needs escaping in Java.
    private static final String CSS =
            "[hidden]{display:none!important}\n"
                    + ".wrap{display:flex;align-items:flex-start}\n"
                    + "#nav{position:sticky;top:0;flex:0 0 230px;height:100vh;overflow:auto;"
                    + "background:var(--teal);color:#fff;padding:16px 14px 40px;font-size:14px}\n"
                    + "#nav input,#nav select{width:100%;margin:0 0 8px;padding:7px 9px;border:0;"
                    + "border-radius:6px;font:inherit;background:#fff;color:var(--ink)}\n"
                    + "#nav a{display:block;color:#fff;text-decoration:none;padding:2px 0}\n"
                    + "#nav a:hover{text-decoration:underline}\n"
                    + "#nav a.y{font-weight:700;margin-top:10px}\n"
                    + "#nav a.m{padding-left:12px;color:rgba(255,255,255,.85)}\n"
                    + ".content{flex:1 1 auto;min-width:0;max-width:860px;padding:20px 20px 64px;"
                    + "text-align:left}\n"
                    + "summary.sh{cursor:pointer;margin:18px 0 10px;font-size:24px;font-weight:700;"
                    + "color:var(--teal);border-bottom:1px solid var(--line);padding-bottom:6px}\n"
                    + "#timeline>summary.sh{margin-top:0}\n"
                    + ".month>h3{margin:20px 0 10px;font-size:13px;font-weight:700;"
                    + "letter-spacing:1.4px;text-transform:uppercase;color:var(--dim)}\n"
                    + ".note{background:var(--card);border:1px solid var(--line);border-radius:10px;"
                    + "padding:14px 18px;margin:0 0 12px;text-align:left}\n"
                    + ".note h4{margin:0;font-size:17px;line-height:1.35;overflow-wrap:anywhere}\n"
                    + ".by{font-size:12.5px;color:var(--dim);margin:3px 0 0}\n"
                    + ".cat{background:var(--line);color:var(--ink);border-radius:999px;"
                    + "padding:1px 9px;margin-left:8px}\n"
                    + ".cat:first-child{margin-left:0}\n"
                    + ".body{margin-top:10px;overflow-wrap:anywhere}\n"
                    + ".body>:first-child{margin-top:0}.body>:last-child{margin-bottom:0}\n"
                    + ".body ul{list-style:disc;padding-left:1.4em}\n"
                    + ".body ol{list-style:decimal;padding-left:1.6em}\n"
                    + ".body li{margin:2px 0}\n"
                    + ".body img{max-width:100%;height:auto;border-radius:6px;cursor:zoom-in}\n"
                    + ".body pre{overflow:auto;background:#eef3f2;padding:10px;border-radius:6px}\n"
                    + ".body table{border-collapse:collapse}\n"
                    + ".body th,.body td{border:1px solid var(--line);padding:4px 8px}\n"
                    + ".body a{color:var(--teal)}\n"
                    + ".omit{color:var(--dim);font-size:13px;margin-top:24px}\n"
                    + "@media (max-width:820px){.wrap{display:block}"
                    + "#nav{position:static;height:auto}}\n"
                    + "@media print{#nav{display:none}.note{break-inside:avoid;border-color:#ccc}}\n";

    private static final String JS =
            "(function(){\n"
                    + "var q=document.getElementById('q'),c=document.getElementById('cat');\n"
                    + "var notes=[].slice.call(document.querySelectorAll('.note')),txt=[];\n"
                    + "var years=[].slice.call(document.querySelectorAll('details.ysec'));\n"
                    + "var wasOpen=years.map(function(d){return d.open;});\n"
                    + "function run(){\n"
                    + "var s=q.value.trim().toLowerCase(),k=c?c.value:'',on=s!==''||k!=='';\n"
                    + "notes.forEach(function(n,i){\n"
                    + "if(txt[i]===undefined){txt[i]=n.textContent.toLowerCase();}\n"
                    + "n.hidden=on&&!((k===''||n.getAttribute('data-cat')===k)"
                    + "&&(s===''||txt[i].indexOf(s)>=0));});\n"
                    + "[].forEach.call(document.querySelectorAll('section.month'),function(m){"
                    + "m.hidden=on&&!m.querySelector('.note:not([hidden])');});\n"
                    + "years.forEach(function(d,i){\n"
                    + "var hit=!!d.querySelector('.note:not([hidden])');\n"
                    + "d.hidden=on&&!hit;d.open=on?hit:wasOpen[i];});\n"
                    + "var t=document.getElementById('timeline');if(t){t.hidden=on;}\n"
                    + "}\n"
                    + "q.addEventListener('input',run);q.addEventListener('search',run);\n"
                    + "if(c){c.addEventListener('change',run);}\n"
                    // A link in the contents column opens the year it points into, which a
                    // closed year would otherwise swallow.
                    + "document.getElementById('nav').addEventListener('click',function(e){\n"
                    + "var a=e.target.closest&&e.target.closest('a');if(!a){return;}\n"
                    + "var el=document.getElementById(a.getAttribute('href').slice(1));if(!el){return;}\n"
                    + "var d=el.tagName==='DETAILS'?el:el.closest('details');if(d){d.open=true;}\n"
                    + "e.preventDefault();el.scrollIntoView();history.replaceState(null,'','#'+el.id);});\n"
                    + "document.addEventListener('click',function(e){var t=e.target;"
                    + "if(t.tagName==='IMG'&&t.closest&&t.closest('.body')){big(t);}});\n"
                    + "})();\n";
}
