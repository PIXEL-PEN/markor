// File: app/src/main/java/net/pixelpen/journal/util/CategoryManager.java
package net.pixelpen.journal.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CategoryManager {
    /**
     * Optional override, for tests. Null means "use the app's home folder", which is what
     * production always wants.
     * <p>
     * This used to be a hardcoded "/storage/emulated/0/Documents/markor default" with a
     * setRoot() that nothing ever called — so category scanning read a folder that had no
     * relation to where notes were actually stored, and silently found nothing. It was a
     * third independent idea of where home was, alongside the two that AppSettings now
     * replaces.
     */
    private static String ROOT = null;

    private static final String PREFS = "category_prefs";
    private static final String PREF_KEY = "custom_categories";
    private static final String PREF_PROTECTED = "protected_category";

    /** What the protected category is called until someone renames it. */
    public static final String DEFAULT_PROTECTED = "General";

    // ---------- Result DTO ----------
    public static class Result {
        public final boolean ok;
        public final String message;
        public final int previewCount;
        public final List<String> changedPaths;
        private Result(boolean ok, String msg, int count, List<String> paths) {
            this.ok = ok; this.message = msg; this.previewCount = count; this.changedPaths = paths;
        }
        public static Result ok(String msg) { return new Result(true, msg, 0, Collections.emptyList()); }
        public static Result ok(String msg, int count, List<String> paths) { return new Result(true, msg, count, paths); }
        public static Result preview(String msg, int count, List<String> paths) { return new Result(true, msg, count, paths); }
        public static Result error(String msg) { return new Result(false, msg, 0, Collections.emptyList()); }
    }

    // ---------- Public config ----------
    /** Test seam only. Pass null to go back to the app's home folder. */
    public static void setRoot(String path) { ROOT = path; }

    /** The app's home folder, unless a test has overridden it. */
    private static File root() {
        return (ROOT != null)
                ? new File(ROOT)
                : net.pixelpen.journal.ApplicationObject.settings().getNotebookDirectory();
    }

    // ---------- Public API ----------
    @NonNull
    /**
     * The Month folder of the Year/Month/Category slot a note sits in, or null if it does not sit
     * in one.
     *
     * <p>The year is the test, because it is the only one of the three whose name has a shape:
     * four digits. A quicknote lives under Quicknotes/Year and an imported file anywhere, and
     * neither has a category to be moved between.
     *
     * <p>Here rather than in either caller because the Tree and the editor both offer the move and
     * must agree on what is movable. Two copies of this would drift.
     */
    public static File journalMonthDir(final File file) {
        final File category = file == null ? null : file.getParentFile();
        final File month = category == null ? null : category.getParentFile();
        final File year = month == null ? null : month.getParentFile();
        return year != null && year.getName().matches("\\d{4}") ? month : null;
    }

    /**
     * The categories a note could move to: every known category except the one it is in.
     */
    public static List<String> moveTargetsFor(final Context ctx, final File file) {
        final List<String> out = new ArrayList<>();
        final File category = file == null ? null : file.getParentFile();
        if (journalMonthDir(file) == null || category == null) {
            return out;
        }
        for (final String c : listCategories(ctx).changedPaths) {
            if (!c.equalsIgnoreCase(category.getName())) {
                out.add(c);
            }
        }
        return out;
    }

    public static Result listCategories(Context ctx) {
        Set<String> cats = new LinkedHashSet<>(scanCategories());
        cats.addAll(loadCustom(ctx));
        List<String> list = new ArrayList<>(cats);
        Collections.sort(list, String::compareToIgnoreCase);
        return Result.ok("OK", list.size(), list);
    }

    @NonNull
    public static Result addCategory(Context ctx, String nameRaw) {
        String name = norm(nameRaw);
        if (!valid(name)) return Result.error("Invalid name");
        Set<String> all = new LinkedHashSet<>(scanCategories());
        all.addAll(loadCustom(ctx));
        for (String s : all) if (s.equalsIgnoreCase(name)) return Result.error("Category exists");
        Set<String> updated = new LinkedHashSet<>(loadCustom(ctx)); updated.add(name);
        saveCustom(ctx, updated);
        return Result.ok("Added: " + name);
    }

    @NonNull
    public static Result renameCategory(Context ctx, String fromRaw, String toRaw, boolean dryRun) {
        String from = norm(fromRaw), to = norm(toRaw);
        if (!valid(from) || !valid(to) || from.equalsIgnoreCase(to)) return Result.error("Invalid");
        List<File> srcFolders = findCategoryFolders(from);
        if (srcFolders.isEmpty()) return Result.error("No folders found");
        List<String> preview = new ArrayList<>();
        for (File src : srcFolders) preview.add(src + " -> " + new File(src.getParent(), to));
        if (dryRun) return Result.preview("Preview rename", preview.size(), preview);

        try {
            for (File src : srcFolders) {
                File tgt = new File(src.getParent(), to);
                JSONObject moveGroup = moveDirPreserveMtimes(src, tgt);
                // also include per-file ops to be extra safe (optional)
                if (moveGroup != null && moveGroup.has("ops")) {
                    JSONArray ops = moveGroup.getJSONArray("ops");
                }
            }
            if (isProtected(ctx, from)) {
                setProtectedCategory(ctx, to);
            }
            return Result.ok("Renamed", preview.size(), preview);
        } catch (Exception e) { return Result.error(e.getMessage()); }
    }

    @NonNull
    public static Result mergeCategories(Context ctx, String sourceRaw, String targetRaw, boolean dryRun) {
        String source = norm(sourceRaw), target = norm(targetRaw);
        if (!valid(source) || !valid(target) || source.equalsIgnoreCase(target)) return Result.error("Invalid");
        // The source is consumed by a merge, so merging it away is a delete wearing a hat.
        if (isProtected(ctx, source)) return refuseProtected(source);
        List<File> srcFolders = findCategoryFolders(source);
        if (srcFolders.isEmpty()) return Result.error("No folders found");
        List<String> preview = new ArrayList<>();
        for (File src : srcFolders) preview.add("Merge " + src + " -> " + new File(src.getParent(), target));
        if (dryRun) return Result.preview("Preview merge", preview.size(), preview);

        try {
            for (File src : srcFolders) {
                File tgt = new File(src.getParent(), target);
                if (!tgt.exists()) // simple rename if target doesn’t exist
                {
                    moveDirPreserveMtimes(src, tgt);
                } else {
                    // merge file-by-file into existing target
                    mergeIntoPreserveMtimes(src, tgt);
                    // delete emptied source
                    deleteEmptyDirs(src);
                }
            }
            return Result.ok("Merged", preview.size(), preview);
        } catch (Exception e) { return Result.error(e.getMessage()); }
    }

    @NonNull
    public static Result deleteCategory(Context ctx, String nameRaw, boolean dryRun, boolean forceMoveToUncategorized) {
        String name = norm(nameRaw);
        if (!valid(name)) return Result.error("Invalid");
        if (isProtected(ctx, name)) return refuseProtected(name);
        List<File> srcFolders = findCategoryFolders(name);
        if (srcFolders.isEmpty()) return Result.error("No folders found");

        String fallback = "Uncategorized";
        List<String> preview = new ArrayList<>();
        for (File src : srcFolders) {
            if (forceMoveToUncategorized) {
                preview.add("Move * " + src + " -> " + new File(src.getParent(), fallback) + " and remove " + src.getName());
            } else {
                preview.add("Delete (must be empty): " + src);
            }
        }
        if (dryRun) return Result.preview("Preview delete", preview.size(), preview);

        try {
            for (File src : srcFolders) {
                if (forceMoveToUncategorized) {
                    File tgt = new File(src.getParent(), fallback);
                    mergeIntoPreserveMtimes(src, tgt);
                    deleteEmptyDirs(src);
                } else {
                    // only delete if empty
                    File[] content = src.listFiles();
                    if (content != null && content.length > 0) {
                        return Result.error("Folder not empty: " + src.getAbsolutePath() + " (use force)");
                    }
                    if (src.delete()) {
                    }
                }
            }
            return Result.ok("Deleted", preview.size(), preview);
        } catch (Exception e) { return Result.error(e.getMessage()); }
    }

    // ---------- Internals ----------
    /**
     * How much is filed under a category: {notes, months it appears in}.
     *
     * <p>So the dialog can say "12 notes across 3 months" before it offers to move or remove
     * them. A category is a folder name repeated under every month that used it, so the second
     * figure is the number of folders and the first is what they hold.
     */
    public static int[] countNotes(final String nameRaw) {
        final String name = norm(nameRaw);
        int notes = 0;
        final List<File> folders = findCategoryFolders(name);
        for (final File f : folders) {
            final File[] kids = f.listFiles();
            if (kids == null) {
                continue;
            }
            for (final File k : kids) {
                if (k.isFile() && !k.getName().startsWith(".")) {
                    notes++;
                }
            }
        }
        return new int[]{notes, folders.size()};
    }

    private static boolean valid(String name) {
        if (name == null) return false;
        String n = name.trim();
        if (n.isEmpty()) return false;
        if (n.contains("/") || n.contains("\\") || n.length() > 120) return false;
        return true;
    }

    /**
     * The one category that cannot be deleted or merged away.
     *
     * <p>Every note is filed under a category, and the Tree and the Year screen are built on
     * that, so an archive with no categories left has nothing to show in either. One is kept
     * back as the floor. It is also the capture screen's fallback when nothing is chosen, which
     * would otherwise be a name pointing at nothing.
     *
     * <p>Held in preferences rather than as a constant so it survives being renamed: rename it
     * and the protection moves to the new name instead of staying behind on a category that no
     * longer exists. Nothing offers that rename yet, but the mechanism does not need revisiting
     * when something does.
     */
    public static String protectedCategory(final Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_PROTECTED, DEFAULT_PROTECTED);
    }

    public static boolean isProtected(final Context ctx, final String name) {
        return norm(name).equalsIgnoreCase(protectedCategory(ctx));
    }

    private static void setProtectedCategory(final Context ctx, final String name) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_PROTECTED, name).apply();
    }

    private static Result refuseProtected(final String name) {
        return Result.error(name + " cannot be removed. Every note needs a category, and the "
                + "Tree and Year screens are built on them. Rename it instead.");
    }

    /**
     * The key two category names share when they are the same category.
     *
     * <p>norm trims, NFKC-normalises and collapses runs of whitespace; this folds case on
     * top. Folder names come off the disk raw, so "Medical-Health" in one month and
     * "medical-health" in another are two strings and one category, and listCategories
     * cannot tell: it merges with a LinkedHashSet, which only dedupes exact matches.
     * addCategory has always compared with equalsIgnoreCase, so typing a case-variant by
     * hand was refused while a bulk add would have let it through.
     */
    public static String key(final String s) {
        return norm(s).toLowerCase(java.util.Locale.ROOT);
    }

    private static String norm(String s) {
        if (s == null) return "";
        String t = s.trim();
        t = Normalizer.normalize(t, Normalizer.Form.NFKC);
        return t.replaceAll("\\s+", " ");
    }

    private static Set<String> loadCustom(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new LinkedHashSet<>(sp.getStringSet(PREF_KEY, Collections.emptySet()));
    }

    private static void saveCustom(Context ctx, Set<String> cats) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(PREF_KEY, cats).apply();
    }

    private static Set<String> scanCategories() {
        Set<String> res = new LinkedHashSet<>();
        File[] years = root().listFiles(onlyDirs()); if (years == null) return res;
        for (File y : years) {
            File[] months = y.listFiles(onlyDirs()); if (months == null) continue;
            for (File m : months) {
                File[] cats = m.listFiles(onlyDirs()); if (cats == null) continue;
                for (File c : cats) res.add(c.getName());
            }
        }
        return res;
    }

    /**
     * The most recent month folder that actually holds notes for this category, or null.
     *
     * <p>For Browse. It pointed at the category folder for the month on the Calendar screen,
     * which for an archive copied in from elsewhere is empty or absent while every note sits
     * under older months: the Tree opened, expanded nothing, and looked broken. Only notes
     * written today appeared, because today is the month Browse was pointing at.
     */
    public static File newestFolderWithNotes(final String nameRaw) {
        File best = null;
        long bestKey = -1;
        for (final File f : findCategoryFolders(norm(nameRaw))) {
            final String[] kids = f.list();
            if (kids == null || kids.length == 0) {
                continue;
            }
            final File month = f.getParentFile();
            final File year = month == null ? null : month.getParentFile();
            if (year == null) {
                continue;
            }
            final long y;
            try {
                y = Long.parseLong(year.getName().trim());
            } catch (final NumberFormatException e) {
                continue;
            }
            // Months are written "01 January", so the leading number orders them properly;
            // without it "April" would sort before "January" and the newest would be wrong.
            final java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("^\\s*(\\d{1,2})").matcher(month.getName());
            final long mo = m.find() ? Long.parseLong(m.group(1)) : 0L;
            final long keyv = y * 100 + mo;
            if (keyv > bestKey) {
                bestKey = keyv;
                best = f;
            }
        }
        return best;
    }

    private static List<File> findCategoryFolders(String name) {
        List<File> res = new ArrayList<>();
        File[] years = root().listFiles(onlyDirs()); if (years == null) return res;
        for (File y : years) {
            File[] months = y.listFiles(onlyDirs()); if (months == null) continue;
            for (File m : months) {
                File[] cats = m.listFiles(onlyDirs()); if (cats == null) continue;
                for (File c : cats) if (c.getName().equalsIgnoreCase(name)) res.add(c);
            }
        }
        return res;
    }

    private static FileFilter onlyDirs() { return f -> f != null && f.isDirectory(); }

    // --- Move/merge helpers with mtime preservation (java.io only) ---
    private static JSONObject moveDirPreserveMtimes(File src, File dst) throws Exception {
        JSONObject group = new JSONObject(); JSONArray ops = new JSONArray();
        if (!src.exists() || !src.isDirectory()) throw new Exception("Not a dir: " + src);
        if (dst.exists()) {
            // merge into dst
            JSONObject mg = mergeIntoPreserveMtimes(src, dst);
            if (mg != null && mg.has("ops")) {
                JSONArray mgops = mg.getJSONArray("ops");
                for (int i = 0; i < mgops.length(); i++) ops.put(mgops.get(i));
            }
            deleteEmptyDirs(src);
        } else {
            if (!dst.getParentFile().exists()) //noinspection ResultOfMethodCallIgnored
                dst.getParentFile().mkdirs();
            if (!src.renameTo(dst)) {
                // fallback: copy then delete
                copyDirPreserveMtimes(src, dst, ops);
                deleteRec(src);
            } else {
                // record one dir move
                ops.put(new JSONObject().put("type","move").put("from", src.getAbsolutePath()).put("to", dst.getAbsolutePath()));
            }
        }
        group.put("ops", ops);
        return group;
    }

    private static JSONObject mergeIntoPreserveMtimes(File src, File dst) throws Exception {
        if (!src.exists() || !src.isDirectory()) throw new Exception("Not a dir: " + src);
        if (!dst.exists()) { //noinspection ResultOfMethodCallIgnored
            dst.mkdirs();
        }
        JSONObject group = new JSONObject(); JSONArray ops = new JSONArray();
        File[] children = src.listFiles();
        if (children != null) {
            for (File ch : children) {
                File tgt = new File(dst, ch.getName());
                if (ch.isDirectory()) {
                    JSONObject inner = mergeIntoPreserveMtimes(ch, tgt);
                    if (inner != null && inner.has("ops")) {
                        JSONArray inOps = inner.getJSONArray("ops");
                        for (int i = 0; i < inOps.length(); i++) ops.put(inOps.get(i));
                    }
                } else {
                    moveFilePreserveMtime(ch, tgt, ops);
                }
            }
        }
        group.put("ops", ops);
        return group;
    }

    private static void movePathPreserveMtime(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            moveDirPreserveMtimes(src, dst);
        } else {
            JSONArray ops = new JSONArray();
            moveFilePreserveMtime(src, dst, ops);
        }
    }

    private static void moveFilePreserveMtime(File src, File dst, JSONArray ops) throws Exception {
        if (!dst.getParentFile().exists()) //noinspection ResultOfMethodCallIgnored
            dst.getParentFile().mkdirs();
        long mtime = src.lastModified();
        if (dst.exists()) {
            // give dst a non-conflicting name by appending number
            File alt = uniqueSibling(dst);
            if (!src.renameTo(alt)) {
                copyFile(src, alt);
                //noinspection ResultOfMethodCallIgnored
                src.delete();
            }
            // restore mtime
            //noinspection ResultOfMethodCallIgnored
            alt.setLastModified(mtime);
            ops.put(new JSONObject().put("type","filemove").put("from", src.getAbsolutePath()).put("to", alt.getAbsolutePath()));
        } else {
            if (!src.renameTo(dst)) {
                copyFile(src, dst);
                //noinspection ResultOfMethodCallIgnored
                src.delete();
            }
            // restore mtime
            //noinspection ResultOfMethodCallIgnored
            dst.setLastModified(mtime);
            ops.put(new JSONObject().put("type","filemove").put("from", src.getAbsolutePath()).put("to", dst.getAbsolutePath()));
        }
    }

    private static File uniqueSibling(File desired) {
        if (!desired.exists()) return desired;
        String name = desired.getName();
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        int n = 1;
        File parent = desired.getParentFile();
        File out;
        do {
            out = new File(parent, base + " (" + n + ")" + ext);
            n++;
        } while (out.exists());
        return out;
    }

    private static void copyDirPreserveMtimes(File src, File dst, JSONArray ops) throws Exception {
        if (!dst.exists()) //noinspection ResultOfMethodCallIgnored
            dst.mkdirs();
        File[] kids = src.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            File t = new File(dst, k.getName());
            if (k.isDirectory()) {
                copyDirPreserveMtimes(k, t, ops);
            } else {
                long m = k.lastModified();
                copyFile(k, t);
                //noinspection ResultOfMethodCallIgnored
                t.setLastModified(m);
                ops.put(new JSONObject().put("type","filemove").put("from", k.getAbsolutePath()).put("to", t.getAbsolutePath()));
            }
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        java.io.FileInputStream in = null;
        java.io.FileOutputStream out = null;
        try {
            in = new java.io.FileInputStream(src);
            out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (out != null) try { out.close(); } catch (Exception ignored) {}
        }
    }

    private static void deleteRec(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /**
     * Remove a directory and any empty directories under it, deepest first.
     *
     * <p>The old version could not delete the directory it was given if that directory had
     * any subdirectory. It popped a folder, pushed the children, re-listed the folder, found
     * the children still there because nothing had deleted them yet, and moved on without
     * ever returning to it. Children went, parents stayed, which is why a category folder
     * sat there empty after its notes had been moved away.
     *
     * <p>Collected first, then deleted in reverse, so a child is always gone before its
     * parent is tried.
     */
    private static void deleteEmptyDirs(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        final List<File> order = new ArrayList<>();
        final Deque<File> stack = new ArrayDeque<>();
        stack.push(f);
        while (!stack.isEmpty()) {
            final File cur = stack.pop();
            order.add(cur);
            final File[] kids = cur.listFiles();
            if (kids != null) {
                for (final File k : kids) {
                    if (k.isDirectory()) {
                        stack.push(k);
                    }
                }
            }
        }
        for (int i = order.size() - 1; i >= 0; i--) {
            final File cur = order.get(i);
            final File[] kids = cur.listFiles();
            if (kids == null || kids.length == 0) {
                //noinspection ResultOfMethodCallIgnored
                cur.delete();
            }
        }
    }
}
