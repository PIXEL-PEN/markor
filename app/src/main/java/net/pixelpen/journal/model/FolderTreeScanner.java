package net.pixelpen.journal.model;

import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Date;
import java.util.Locale;

public class FolderTreeScanner {

    // Perf knobs for very large folders
    private static final int CREATED_SORT_THRESHOLD = 500;
    private static final int DIR_PREFIX_SORT_THRESHOLD = 400;

    // Single-thread executor for background scans
    private static final java.util.concurrent.ExecutorService EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    // Simple callback interface
    public interface TreeCallback { void onResult(FolderNode node); }

    /** Run scan() off the UI thread and post result back to the main thread. */
    public static void scanAsync(final java.io.File root,
                                 final String selectedCategory,
                                 final String selectedMonth,
                                 final android.os.Handler uiHandler,
                                 final TreeCallback cb) {
        EXEC.submit(new Runnable() {
            @Override public void run() {
                final FolderNode tree = scan(root, selectedCategory, selectedMonth);
                uiHandler.post(new Runnable() {
                    @Override public void run() { cb.onResult(tree); }
                });
            }
        });
    }

    // Matches leading numeric prefix like "01_January" or "1-January"
    private static final Pattern LEADING_NUM = Pattern.compile("^(\\d{1,2})[_-].*");

    // Strip suffix for display names (md/markdown/txt only)
    private static final Pattern STRIP_SUFFIX =
            Pattern.compile("\\.(md|markdown|txt)$", Pattern.CASE_INSENSITIVE);

    // Allowed document extensions for the tree (lowercase)
    private static final Set<String> DOC_EXTS = new HashSet<>();
    static {
        // text-ish
        DOC_EXTS.add("md");
        DOC_EXTS.add("markdown");
        DOC_EXTS.add("txt");
        // html
        DOC_EXTS.add("html");
        DOC_EXTS.add("htm");
    }

    // ---- Creation index integration ----
    private static net.pixelpen.journal.model.CreationIndex sCreationIndex;

    public static void setCreationIndex(net.pixelpen.journal.model.CreationIndex idx) {
        sCreationIndex = idx;
    }

    // Skip marker helper
    private static boolean shouldSkipDir(java.io.File dir) {
        return new java.io.File(dir, ".nomedia").exists()
                || new java.io.File(dir, ".skip-scan").exists();
    }

    /**
     * Lookup stable "created" time; lazily backfill (one-time) when missing.
     */
    private static long getIndexedCreated(File f) {
        if (sCreationIndex != null) {
            Long v = sCreationIndex.get(f);
            if (v != null && v > 0L) {
                return v;
            }
            long created = f.lastModified();
            sCreationIndex.put(f, created);
            return created;
        }
        return f.lastModified();
    }
    // ---- /Creation index integration ----

    /**
     * Public entry: scan the tree and mark expansions so UI can show
     * root > year > selectedMonth > selectedCategory expanded.
     *
     * @param root             the Markor root folder (e.g., /Documents/markor default)
     * @param selectedCategory e.g., "General"
     * @param selectedMonth    e.g., "08_July", "July", "Jul", "07", "7", "July 2025", "2025-07"
     */
    public static FolderNode scan(File root, String selectedCategory, String selectedMonth) {
        if (root == null || !root.exists() || !root.isDirectory()) {
            Log.w("FolderTree", "scan(): invalid root: " + root);
            return null;
        }
        FolderNode tree = scanRecursive(root, 0, selectedCategory, selectedMonth, false);
        if (tree != null) {
            tree.expanded = true;
        }
        if (sCreationIndex != null) {
            sCreationIndex.save();
        }
        return tree;
    }

    /**
     * Recursively build the tree, sorting directories by numeric prefix (01..12) first,
     * and expanding only along the path (year -> month -> category).
     *
     * @param dir               current directory
     * @param depth             0=root, 1=year, 2=month, 3=category, ...
     * @param selectedCategory  category name to expand inside the selectedMonth
     * @param selectedMonth     month indicator (see scan() javadoc)
     * @param monthMatchedAbove true if an ancestor already matched the selectedMonth
     */
    private static FolderNode scanRecursive(File dir,
                                            int depth,
                                            String selectedCategory,
                                            String selectedMonth,
                                            boolean monthMatchedAbove) {
        FolderNode node = new FolderNode(dir.getName(), dir);
        node.depth = depth;

        File[] entries = dir.listFiles();
        if (entries == null) {
            return node;
        }

        // Partition entries to dirs & files; filter hidden/.res/_res and allow md/markdown/txt/html/htm
        List<File> dirs = new ArrayList<>();
        List<File> docs = new ArrayList<>();
        for (File f : entries) {
            String name = f.getName();
            if (name.startsWith(".") || name.equals(".res") || name.equals("_res")) {
                continue;
            }
            if (f.isDirectory()) {
                dirs.add(f);
            } else if (f.isFile() && isDoc(f)) {
                docs.add(f);
            }
        }

        // Sort directories: prefer numeric prefix, but use fast alpha for huge directories
        if (dirs.size() > DIR_PREFIX_SORT_THRESHOLD) {
            // Fast path for many subfolders
            Collections.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        } else {
            sortSubfoldersByPrefix(dirs);
        }

        // Sort files: prefer indexed created time (newest → oldest), but switch to fast alpha for huge sets
        if (docs.size() > CREATED_SORT_THRESHOLD) {
            // Fast path: avoid getIndexedCreated() calls on every file
            Collections.sort(docs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        } else {
            Collections.sort(docs, (a, b) -> Long.compare(getIndexedCreated(b), getIndexedCreated(a)));
        }

        // Determine if this node is the selected month folder (robust match)
        boolean thisIsSelectedMonth = (depth == 2) && monthMatches(dir.getName(), selectedMonth);

        // Recurse into children
        for (File d : dirs) {
            if (shouldSkipDir(d)) {
                continue;
            }
            FolderNode child = scanRecursive(
                    d,
                    depth + 1,
                    selectedCategory,
                    selectedMonth,
                    monthMatchedAbove || thisIsSelectedMonth
            );
            node.subfolders.add(child);
        }

        // Attach files
        for (File f : docs) {
            String displayName = renderDisplayName(f.getName());
            node.files.add(new FileNode(displayName, f, depth + 1));
        }

        // Expansion logic
        if (depth == 1) {
            if (containsMonth(node, selectedMonth)) {
                node.expanded = true;
            }
        } else if (depth == 2) {
            if (thisIsSelectedMonth) {
                node.expanded = true;
            }
        } else if (depth == 3) {
            if (monthMatchedAbove && selectedCategory != null
                    && equalsIgnoreCaseSafe(dir.getName(), selectedCategory)) {
                node.expanded = true;
            }
        }

        return node;
    }

    // ----- Helpers -----

    public static String formatShortDate(long timeMillis) {
        return new SimpleDateFormat("EEE | d MMM | yy", Locale.getDefault())
                .format(new Date(timeMillis));
    }

    private static boolean containsMonth(FolderNode yearNode, String selectedMonth) {
        if (selectedMonth == null) return false;
        for (FolderNode m : yearNode.subfolders) {
            if (monthMatches(m.name, selectedMonth)) {
                return true;
            }
        }
        return false;
    }

    private static boolean equalsIgnoreCaseSafe(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    /** Match month folder by many common inputs: "07_July", "July", "Jul", "07", "7", "July 2025", "2025-07", "07/2025". */
    private static boolean monthMatches(String folderName, String selectedMonth) {
        if (selectedMonth == null) return false;

        String folder = folderName.toLowerCase(Locale.ROOT);
        String sel = selectedMonth.toLowerCase(Locale.ROOT).trim();

        // Exact match
        if (folder.equals(sel)) return true;

        // Get suffix "July" from "07_July" (or no underscore -> whole name)
        int us = folder.indexOf('_');
        String suffix = (us >= 0 && us < folder.length() - 1) ? folder.substring(us + 1) : folder;

        // Abbrev (Jul), and numeric (07 / 7) from folder
        String abbrev = suffix.length() >= 3 ? suffix.substring(0, 3) : suffix;
        int monthNum = monthNumberFromName(suffix); // 1..12 or -1
        String mm = (monthNum > 0) ? String.format(Locale.ROOT, "%02d", monthNum) : "";
        String m = (monthNum > 0) ? Integer.toString(monthNum) : "";

        // Accept if selectedMonth contains any reasonable token
        if (!suffix.isEmpty() && (sel.equals(suffix) || sel.contains(suffix))) return true;
        if (!abbrev.isEmpty() && (sel.equals(abbrev) || sel.contains(abbrev))) return true;
        if (!mm.isEmpty() && (sel.equals(mm) || sel.contains("-" + mm) || sel.contains("/" + mm) || sel.contains(mm + "/") || sel.contains(mm + "-"))) return true;
        if (!m.isEmpty()  && (sel.equals(m)  || sel.contains("-" + m)  || sel.contains("/" + m)  || sel.contains(m + "/")  || sel.contains(m + "-")))  return true;

        return false;
    }

    private static int monthNumberFromName(String nameLower) {
        String n = nameLower.toLowerCase(Locale.ROOT);
        if (n.startsWith("jan")) return 1;
        if (n.startsWith("feb")) return 2;
        if (n.startsWith("mar")) return 3;
        if (n.startsWith("apr")) return 4;
        if (n.startsWith("may")) return 5;
        if (n.startsWith("jun")) return 6;
        if (n.startsWith("jul")) return 7;
        if (n.startsWith("aug")) return 8;
        if (n.startsWith("sep")) return 9;
        if (n.startsWith("oct")) return 10;
        if (n.startsWith("nov")) return 11;
        if (n.startsWith("dec")) return 12;
        return -1;
    }

    private static void sortSubfoldersByPrefix(List<File> dirs) {
        if (dirs.size() > DIR_PREFIX_SORT_THRESHOLD) {
            // Fast path: simple alpha sort for huge directories
            Collections.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            return;
        }
        // Original behavior with leading-number preference
        Collections.sort(dirs, (a, b) -> {
            int na = extractLeadingNumber(a.getName());
            int nb = extractLeadingNumber(b.getName());

            if (na != Integer.MAX_VALUE || nb != Integer.MAX_VALUE) {
                int cmp = Integer.compare(na, nb);
                if (cmp != 0) return cmp;
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });
    }

    private static int extractLeadingNumber(String name) {
        Matcher m = LEADING_NUM.matcher(name);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
            }
        }
        return Integer.MAX_VALUE;
    }

    // -- New helpers for bug #2 --

    private static boolean isDoc(File f) {
        String name = f.getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot + 1);
        return DOC_EXTS.contains(ext);
    }

    /** Strip extension for md/markdown/txt only; keep .html/.htm visible. */
    private static String renderDisplayName(String fileName) {
        return STRIP_SUFFIX.matcher(fileName).replaceAll("");
    }
}
