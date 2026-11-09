    // --- NoteTimestamp stub, using the real patterns ------------------------
    static class NoteTimestamp {
        static String yearFolderName(final Calendar c) {
            return new java.text.SimpleDateFormat("yyyy", java.util.Locale.ENGLISH).format(c.getTime());
        }
        static String monthFolderName(final Calendar c) {
            return new java.text.SimpleDateFormat("MM_MMMM", java.util.Locale.ENGLISH).format(c.getTime());
        }
    }

    // --- what collect() can actually draw -----------------------------------
    /**
     * Mirrors FolderTreeFragment.collect: a folder's row appears only if the folder exists AND
     * every ancestor up to the root exists and is in _expanded. This is the reachability rule
     * that a recorded-but-absent path silently failed.
     */
    private boolean drawable(final File target) {
        if (!target.isDirectory()) return false;
        for (File p = target.getParentFile(); p != null; p = p.getParentFile()) {
            if (!p.isDirectory()) return false;
            if (!_expanded.contains(p.getAbsolutePath())) return false;
            if (p.equals(_root)) break;
        }
        return _expanded.contains(_root.getAbsolutePath());
    }

    // --- harness ------------------------------------------------------------
    private static int failures = 0;
    private static void check(final String what, final boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
        if (!ok) failures++;
    }

    private boolean noPhantomPaths() {
        for (final String p : _expanded) {
            if (!new File(p).isDirectory()) {
                System.out.println("          phantom in _expanded: " + p);
                return false;
            }
        }
        return true;
    }

    public static void main(final String[] args) throws Exception {
        final File root = new File(args[0]);
        // An archive shaped like an imported one: a category whose notes are all in an old
        // month, and a current month that has never seen it.
        final File oldMed  = new File(root, "2024/03_March/Medical-Health");
        final File oldRef  = new File(root, "2024/03_March/Reference");
        final File nowGen  = new File(root, "2026/11_November/General");
        for (final File d : new File[]{oldMed, oldRef, nowGen}) {
            if (!d.mkdirs()) throw new IllegalStateException("mkdirs " + d);
            new File(d, "note.md").createNewFile();
        }
        final File nowMed = new File(root, "2026/11_November/Medical-Health"); // never created

        System.out.println("Archive: " + root);
        System.out.println();

        // ---- 1. the reported bug: dropdown handed over this month's folder, absent ----
        System.out.println("1. Dropdown points at a category with nothing in this month");
        TreeExpansionTest t = new TreeExpansionTest();
        t._root = root; t._focus = nowMed;
        t.expandCategoryOnNextDraw(nowMed);
        t.seedExpansion(root);
        check("records no path that is not on disk", t.noPhantomPaths());
        check("the month still opens", t._expanded.contains(new File(root, "2026/11_November").getAbsolutePath()));
        check("absent category is not claimed as opened", t._seededCategory == null);
        System.out.println();

        // ---- 2. Browse resolves to the month that actually holds the notes ----
        System.out.println("2. Browse resolves to the newest month holding that category");
        t = new TreeExpansionTest();
        t._root = root; t._focus = oldMed;
        t.expandCategoryOnNextDraw(oldMed);
        t.seedExpansion(root);
        check("no phantom paths", t.noPhantomPaths());
        check("the category row is reachable by collect()", t.drawable(oldMed));
        check("its month is open", t._expanded.contains(new File(root, "2024/03_March").getAbsolutePath()));
        System.out.println();

        // ---- 3. only ever one category open from this mechanism ----
        System.out.println("3. Choosing a second category closes the first");
        t.expandCategoryOnNextDraw(oldRef);
        t.seedExpansion(root);
        check("second category reachable", t.drawable(oldRef));
        check("first category closed", !t._expanded.contains(oldMed.getAbsolutePath()));
        System.out.println();

        // ---- 4. a fallback must not close the month on the next request ----
        System.out.println("4. A fallback to the month does not get closed later");
        t = new TreeExpansionTest();
        t._root = root; t._focus = nowMed;
        t.expandCategoryOnNextDraw(nowMed);   // falls back to the month
        t.seedExpansion(root);
        t.expandCategoryOnNextDraw(oldMed);   // a real one next
        t.seedExpansion(root);
        check("November is still open", t._expanded.contains(new File(root, "2026/11_November").getAbsolutePath()));
        check("March category reachable", t.drawable(oldMed));
        System.out.println();

        // ---- 5. collapse leaves year and month, removes categories ----
        System.out.println("5. Leaving the tree collapses categories only");
        t.collapseCategories();
        check("category closed", !t._expanded.contains(oldMed.getAbsolutePath()));
        check("year kept", t._expanded.contains(new File(root, "2024").getAbsolutePath()));
        check("month kept", t._expanded.contains(new File(root, "2024/03_March").getAbsolutePath()));
        check("root kept", t._expanded.contains(root.getAbsolutePath()));
        check("nothing left to close", t._seededCategory == null);
        System.out.println();

        // ---- 6. a plain revisit opens nothing ----
        System.out.println("6. A plain revisit unfolds nothing");
        final Set<String> before = new HashSet<>(t._expanded);
        t.seedExpansion(root);               // no pending request
        check("expansion unchanged", before.equals(t._expanded));
        System.out.println();

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
