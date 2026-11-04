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
    private static final String UNDO_FILE = ".category_undo.json";

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
            JSONArray undo = new JSONArray();
            for (File src : srcFolders) {
                File tgt = new File(src.getParent(), to);
                JSONObject moveGroup = moveDirPreserveMtimes(src, tgt);
                // log one high-level move for undo
                undo.put(new JSONObject().put("type","move").put("from", src.getAbsolutePath()).put("to", tgt.getAbsolutePath()));
                // also include per-file ops to be extra safe (optional)
                if (moveGroup != null && moveGroup.has("ops")) {
                    JSONArray ops = moveGroup.getJSONArray("ops");
                    for (int i = 0; i < ops.length(); i++) undo.put(ops.get(i));
                }
            }
            writeUndo(undo);
            return Result.ok("Renamed", preview.size(), preview);
        } catch (Exception e) { return Result.error(e.getMessage()); }
    }

    @NonNull
    public static Result mergeCategories(Context ctx, String sourceRaw, String targetRaw, boolean dryRun) {
        String source = norm(sourceRaw), target = norm(targetRaw);
        if (!valid(source) || !valid(target) || source.equalsIgnoreCase(target)) return Result.error("Invalid");
        List<File> srcFolders = findCategoryFolders(source);
        if (srcFolders.isEmpty()) return Result.error("No folders found");
        List<String> preview = new ArrayList<>();
        for (File src : srcFolders) preview.add("Merge " + src + " -> " + new File(src.getParent(), target));
        if (dryRun) return Result.preview("Preview merge", preview.size(), preview);

        try {
            JSONArray undo = new JSONArray();
            for (File src : srcFolders) {
                File tgt = new File(src.getParent(), target);
                if (!tgt.exists()) // simple rename if target doesn’t exist
                {
                    JSONObject mv = moveDirPreserveMtimes(src, tgt);
                    undo.put(new JSONObject().put("type","move").put("from", src.getAbsolutePath()).put("to", tgt.getAbsolutePath()));
                    if (mv != null && mv.has("ops")) {
                        JSONArray ops = mv.getJSONArray("ops");
                        for (int i = 0; i < ops.length(); i++) undo.put(ops.get(i));
                    }
                } else {
                    // merge file-by-file into existing target
                    JSONObject mg = mergeIntoPreserveMtimes(src, tgt);
                    if (mg != null && mg.has("ops")) {
                        JSONArray ops = mg.getJSONArray("ops");
                        for (int i = 0; i < ops.length(); i++) undo.put(ops.get(i));
                    }
                    // delete emptied source
                    deleteEmptyDirs(src);
                    undo.put(new JSONObject().put("type","mkdir").put("path", src.getAbsolutePath())); // recreate on undo
                }
            }
            writeUndo(undo);
            return Result.ok("Merged", preview.size(), preview);
        } catch (Exception e) { return Result.error(e.getMessage()); }
    }

    @NonNull
    public static Result deleteCategory(Context ctx, String nameRaw, boolean dryRun, boolean forceMoveToUncategorized) {
        String name = norm(nameRaw);
        if (!valid(name)) return Result.error("Invalid");
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
            JSONArray undo = new JSONArray();
            for (File src : srcFolders) {
                if (forceMoveToUncategorized) {
                    File tgt = new File(src.getParent(), fallback);
                    JSONObject mg = mergeIntoPreserveMtimes(src, tgt);
                    if (mg != null && mg.has("ops")) {
                        JSONArray ops = mg.getJSONArray("ops");
                        for (int i = 0; i < ops.length(); i++) undo.put(ops.get(i)); // per-file moves
                    }
                    deleteEmptyDirs(src);
                    undo.put(new JSONObject().put("type","mkdir").put("path", src.getAbsolutePath()));
                } else {
                    // only delete if empty
                    File[] content = src.listFiles();
                    if (content != null && content.length > 0) {
                        return Result.error("Folder not empty: " + src.getAbsolutePath() + " (use force)");
                    }
                    if (src.delete()) {
                        undo.put(new JSONObject().put("type","mkdir").put("path", src.getAbsolutePath()));
                    }
                }
            }
            writeUndo(undo);
            return Result.ok("Deleted", preview.size(), preview);
        } catch (Exception e) { return Result.error(e.getMessage()); }
    }

    @NonNull
    public static Result undoLast(Context ctx) {
        File uf = new File(root(), UNDO_FILE);
        if (!uf.exists()) return Result.error("No undo");
        try {
            JSONArray ops = readUndo(uf);
            // reverse order
            for (int i = ops.length() - 1; i >= 0; i--) {
                JSONObject op = ops.getJSONObject(i);
                String type = op.optString("type", "");
                switch (type) {
                    case "move": {
                        File to = new File(op.getString("to"));
                        File from = new File(op.getString("from"));
                        // swap back
                        movePathPreserveMtime(to, from);
                        break;
                    }
                    case "filemove": {
                        File to = new File(op.getString("to"));
                        File from = new File(op.getString("from"));
                        movePathPreserveMtime(to, from);
                        break;
                    }
                    case "mkdir": {
                        File p = new File(op.getString("path"));
                        if (!p.exists()) //noinspection ResultOfMethodCallIgnored
                            p.mkdirs();
                        break;
                    }
                    case "rmdir": {
                        File p = new File(op.getString("path"));
                        deleteEmptyDirs(p);
                        break;
                    }
                    default:
                        // ignore
                        break;
                }
            }
            // clear undo after applying
            //noinspection ResultOfMethodCallIgnored
            uf.delete();
            return Result.ok("Undo complete");
        } catch (Exception e) { return Result.error(e.getMessage()); }
    }

    // ---------- Internals ----------
    private static boolean valid(String name) {
        if (name == null) return false;
        String n = name.trim();
        if (n.isEmpty()) return false;
        if (n.contains("/") || n.contains("\\") || n.length() > 120) return false;
        return true;
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

    private static void deleteEmptyDirs(File f) {
        if (f == null || !f.exists()) return;
        Deque<File> stack = new ArrayDeque<>();
        stack.push(f);
        while (!stack.isEmpty()) {
            File cur = stack.pop();
            File[] kids = cur.listFiles();
            if (kids != null) for (File k : kids) if (k.isDirectory()) stack.push(k);
            kids = cur.listFiles();
            if (kids == null || kids.length == 0) {
                //noinspection ResultOfMethodCallIgnored
                cur.delete();
            }
        }
    }

    // ---------- Undo I/O ----------
    private static void writeUndo(JSONArray undo) throws Exception {
        File f = new File(root(), UNDO_FILE);
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(f, false))) {
            bw.write(undo.toString(2));
        }
    }

    private static JSONArray readUndo(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return new JSONArray(sb.toString());
    }
}
