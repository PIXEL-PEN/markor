package net.pixelpen.journal.activity;

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.TextView;

import net.pixelpen.journal.R;
import net.pixelpen.journal.model.Document;
import net.pixelpen.journal.model.FileNode;
import net.pixelpen.journal.model.FolderNode;
import net.pixelpen.journal.model.FolderTreeScanner;
import net.pixelpen.journal.util.NoteTimestamp;
import net.pixelpen.journal.util.ShortcutUtils;
import net.pixelpen.journal.ApplicationObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FolderTreeFragment extends AutumnBaseFragment {

    public static final String FRAGMENT_TAG = "FolderTreeFragment";

    public static FolderTreeFragment newInstance() {
        return new FolderTreeFragment();
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.fragment_folder_tree;
    }

    @Override
    public String getFragmentTag() {
        return FRAGMENT_TAG;
    }


    private static final String TAG = "WebViewDebug";

    private static final int TITLE_LEN_THRESHOLD = 120;           // chars (without .md)
    private static final long SIZE_THRESHOLD_BYTES = 2L * 1024L;  // 2 KB tiny-size heuristic

    private WebView webView;
    private File rootFolder;

    // [onCreate] FolderTreeFragment — revert to first Loading… iteration (async scan, full hierarchy)
    @Override
    public void onViewCreated(@NonNull final View view, final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Shortcuts refresh (safe no-op if missing)
        if (android.os.Build.VERSION.SDK_INT >= 25) {
            android.content.pm.ShortcutManager sm = requireContext().getSystemService(android.content.pm.ShortcutManager.class);
            if (sm != null) sm.removeAllDynamicShortcuts();
        }
        // Direct call. This used to go through
        // Class.forName("net.gsantner.markor.util.ShortcutUtils") with a reflective invoke, for a
        // class that lives in this very project — which meant the compiler could not see the
        // dependency, and the hardcoded package name broke silently (inside a
        // `catch (Throwable ignored)`) the moment the package moved. Which it since has.
        try {
            ShortcutUtils.updateShortcuts(requireContext());
        } catch (Exception ignored) {
        }


        net.pixelpen.journal.model.CreationIndex cidx = new net.pixelpen.journal.model.CreationIndex(requireContext());
        net.pixelpen.journal.model.FolderTreeScanner.setCreationIndex(cidx);

        webView = view.findViewById(R.id.folder_browser_webview);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);
        webView.clearFocus();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    android.webkit.WebResourceRequest request
            ) {
                String url = (request != null && request.getUrl() != null)
                        ? request.getUrl().toString()
                        : "";
                return handleUrl(url);
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            private boolean handleUrl(String url) {
                Log.d(TAG, "Tapped URL: " + url);
                if (url == null) return false;

                if (url.startsWith("note:")) {
                    String filePath = android.net.Uri.decode(url.substring("note:".length()));
                    openInMarkor(filePath);
                    return true;
                }
                if (url.startsWith("file://")) {
                    String filePath = android.net.Uri.parse(url).getPath();
                    openInMarkor(filePath);
                    return true;
                }
                return false;
            }

            private void openInMarkor(String filePath) {
                try {
                    try {
                        View focused = (getActivity() != null) ? getActivity().getCurrentFocus() : null;
                        if (focused != null) focused.clearFocus();
                        if (webView != null) webView.clearFocus();
                        android.view.inputmethod.InputMethodManager imm =
                                (android.view.inputmethod.InputMethodManager) requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            View anchor = (focused != null) ? focused : webView;
                            if (anchor != null) imm.hideSoftInputFromWindow(anchor.getWindowToken(), 0);
                        }
                    } catch (Throwable ignored) {}

                    File requested = new File(filePath);
                    File target = requested.exists() ? requested : findBestMatch(requested);

                    if (target != null && target.exists()) {
                        Intent intent = new Intent(requireContext(), net.pixelpen.journal.activity.DocumentActivity.class);
                        intent.putExtra(net.pixelpen.journal.model.Document.EXTRA_FILE, target);
                        intent.putExtra(net.pixelpen.journal.model.Document.EXTRA_DO_PREVIEW, true);
                        startActivity(intent);
                    } else {
                        android.widget.Toast.makeText(requireContext(), "File not found:\n" + filePath, android.widget.Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    android.widget.Toast.makeText(requireContext(), "Unable to open file.", android.widget.Toast.LENGTH_SHORT).show();
                }
            }

            private File findBestMatch(File expectedPath) {
                if (expectedPath == null) return null;
                File dir = expectedPath.getParentFile();
                if (dir == null || !dir.isDirectory()) return null;

                String wanted = normalizeName(expectedPath.getName());
                File best = null;
                int bestScore = -1;

                File[] list = dir.listFiles();
                if (list == null) return null;

                for (File f : list) {
                    if (!f.isFile()) continue;
                    String cand = normalizeName(f.getName());

                    int score = 0;
                    if (cand.equals(wanted)) score = 1000;
                    else if (cand.startsWith(wanted) || wanted.startsWith(cand)) score = 900;
                    else if (cand.contains(wanted) || wanted.contains(cand)) score = 800;

                    if (score > bestScore) {
                        bestScore = score;
                        best = f;
                    }
                }
                return (bestScore >= 800) ? best : null;
            }

            private String normalizeName(String name) {
                String n = name;
                int dot = n.lastIndexOf('.');
                if (dot > 0) n = n.substring(0, dot);
                return n.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "");
            }
        });

        reload();
    }

    /**
     * Rescan the archive and redraw.
     * <p>
     * Called on first view and again whenever the Tree tab becomes visible. Without the second
     * call, a note captured on the Journal tab would not appear here until the fragment was
     * recreated -- which breaks the one loop this app exists for: capture, then see it in place.
     * <p>
     * The scan walks the whole archive on every call, so this is also the thing to make lazy
     * when the native tree replaces the WebView.
     */
    public void reload() {
        if (!isAdded() || webView == null) {
            return;
        }

        // The single home folder. Was StorageRoots.getWorkingRoot(), a second independent
        // root that could and did disagree with the one the file browser used.
        rootFolder = ApplicationObject.settings().getNotebookDirectory();
        final java.io.File rf = rootFolder;
        // These were Intent extras from the Calendar screen when the Tree was its own Activity.
        // As a tab it simply opens on the current month; a later change can let the Journal tab
        // drive the selection, at which point these become fields set by the host.
        final String cat = null;
        final String mon = null;

        webView.loadDataWithBaseURL(
                null,
                "<html><body style='font-family:sans-serif;padding:16px'>Loading…</body></html>",
                "text/html",
                "utf-8",
                null
        );

        // Default to the current month's FOLDER name ("11_November"), produced by the same
        // Locale.ENGLISH-pinned formatter that created the folder. This previously formatted
        // "MMMM" here with Locale.ENGLISH while CalendarActivity created the folder with
        // Locale.getDefault(), so on a non-English device nothing ever auto-expanded.
        final String targetMonth = (mon != null && !mon.trim().isEmpty())
                ? mon
                : NoteTimestamp.currentMonthFolderName();

        new Thread(() -> {
            FolderNode tree = FolderTreeScanner.scan(rf, cat, targetMonth);
            String html = buildHtml(tree, cat, targetMonth);

            final android.app.Activity host = getActivity();
            if (host == null) {
                return;
            }
            host.runOnUiThread(() -> {
                // isAdded() guards the fragment being detached while the scan was running.
                if (isAdded() && webView != null) {
                    webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
                }
            });
        }).start();
    }


    private String buildHtml(FolderNode root, String selectedCategory, String selectedMonth) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head>")
                .append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
                .append("<style>")
                .append("body { font-family: sans-serif; padding: 16px; font-size: 17px; }")
                .append(".folder { cursor: pointer; display: flex; align-items: center; margin: 10px 0; }")
                .append(".arrow { display: inline-block; width: 0.80em; transition: transform 0.2s; margin-right: 6px; color: #d35400; }")
                .append(".folder.collapsed .arrow { transform: rotate(0deg); }")
                .append(".folder.expanded .arrow { transform: rotate(90deg); }")
                .append(".file { display: flex; align-items: flex-start; margin: 8px 0 0; font-size: 16px; gap: 10px; padding-bottom: 6px; border-bottom: 1px solid #ddd; }")
                .append(".file .icon { width:28px; position:relative; display:flex; align-items:flex-start; justify-content:center; margin-top:3px; line-height:1; }")
                .append(".file .icon .doc { font-size:24px!important; position:relative; top:3px; display:inline-block; transform:translateY(1px); color:#1c1c1c; }")
                .append(".dot-flag { width:8px; height:8px; border-radius:50%; position:absolute; bottom:0; right:0; }")
                .append(".dot-flag.full { background:#d35400; }")
                .append(".dot-flag.empty { background:transparent; border:1px solid #d35400; }")
                .append(".note-title { font-size: 17px; font-weight: 500; margin-left:2px; }")
                .append(".note-meta  { font-size: 12px; color: #6e6e6e; margin-left: 0; white-space: nowrap; display: block; }")
                .append(".note-meta span.sep { padding: 0 2px; }")
                .append(".year { font-size: 19px; font-weight: bold; }")
                .append(".month { font-size: 18px; font-weight: bold; }")
                .append(".category { font-size: 16px; font-weight: bold; }")
                .append("a { text-decoration: none; color: #222; }")
                .append("a:hover { text-decoration: underline; color: #000; }")
                .append("</style>")
                .append("<script>")
                .append("function toggle(id, labelId) {")
                .append("  var e = document.getElementById(id);")
                .append("  var label = document.getElementById(labelId);")
                .append("  if (e.style.display === 'none') {")
                .append("    e.style.display = 'block';")
                .append("    label.classList.add('expanded');")
                .append("    label.classList.remove('collapsed');")
                .append("  } else {")
                .append("    e.style.display = 'none';")
                .append("    label.classList.add('collapsed');")
                .append("    label.classList.remove('expanded');")
                .append("  }")
                .append("}")
                .append("</script>")
                .append("</head><body>");

        appendFolderHtml(sb, root, 0);
        sb.append("</body></html>");
        return sb.toString();
    }

    private void appendFolderHtml(StringBuilder sb, FolderNode folder, int depth) {
        String id = "f" + folder.hashCode();
        String folderIndent = "margin-left: " + (depth * 20) + "px;";
        String expandByDefault = folder.expanded ? "block" : "none";

        String levelClass = "";
        if (depth == 1) levelClass = "year";
        else if (depth == 2) levelClass = "month";
        else if (depth == 3) levelClass = "category";

        String labelClass = "folder " + levelClass + (folder.expanded ? " expanded" : " collapsed");
        String displayName = folder.name.replaceFirst("^\\d{2}_", "");

        sb.append("<div id='label_").append(id).append("' class='").append(labelClass)
                .append("' style='").append(folderIndent).append("' onclick=\"toggle('").append(id).append("','label_").append(id).append("')\">")
                .append("<span class='arrow'>▶</span>")
                .append(esc(displayName))
                .append("</div>");

        sb.append("<div id='").append(id).append("' style='display:").append(expandByDefault).append(";'>");

        for (FolderNode sub : folder.subfolders) {
            appendFolderHtml(sb, sub, depth + 1);
        }

        String fileIndent = "margin-left: " + ((depth * 20) + 20) + "px;";
        SimpleDateFormat sdfWk = new SimpleDateFormat("EEE", Locale.getDefault());
        SimpleDateFormat sdfDay = new SimpleDateFormat("d",   Locale.getDefault());
        SimpleDateFormat sdfMon = new SimpleDateFormat("MMM", Locale.getDefault());
        SimpleDateFormat sdfYr  = new SimpleDateFormat("yy",  Locale.getDefault());

        for (FileNode file : folder.files) {
            Date t = new Date(file.file.lastModified());
            String dateStr = "<div class='note-meta'>"
                    + sdfWk.format(t) + "<span class='sep'>|</span>"
                    + sdfDay.format(t) + " " + sdfMon.format(t) + "<span class='sep'>|</span>"
                    + sdfYr.format(t)
                    + "</div>";

            String baseName = file.name.replaceAll("\\.md$", "");
            boolean isLongTitle = baseName.length() >= TITLE_LEN_THRESHOLD;
            boolean substantial =
                    (file.file != null && file.file.length() >= SIZE_THRESHOLD_BYTES)
                            || (file.file != null && hasAnyContentBeyondSeed(file.file));

            String iconHtml = "<span class='doc'>📄</span>";
            if (isLongTitle) {
                String dotClass = substantial ? "dot-flag full" : "dot-flag empty";
                iconHtml += "<span class='" + dotClass + "'></span>";
            }

            sb.append("<div class='file' style='").append(fileIndent).append("'>")
                    .append("<div class='icon'>").append(iconHtml).append("</div>")
                    .append("<div>")
                    .append("<a href='note:").append(escHref(file.file.getAbsolutePath())).append("'>")
                    .append("<span class='note-title'>").append(esc(baseName)).append("</span>")
                    .append("</a>")
                    .append(dateStr)
                    .append("</div>")
                    .append("</div>");
        }
        sb.append("</div>");
    }

    /**
     * HTML-escape text that is interpolated into the generated page.
     * <p>
     * Note titles and folder names went in raw. CalendarActivity's filename sanitiser strips
     * \ / : * ? " &lt; &gt; | but leaves apostrophes and ampersands alone, and the attributes
     * here are single-quoted — so a note called "Dad's 80th" produced
     * {@code <a href='note:/.../Dad's 80th.md'>}, where the attribute ends at the apostrophe.
     * The link then pointed at a truncated path and the rest of the title was parsed as stray
     * attributes.
     * <p>
     * This is also what findBestMatch() below has been quietly compensating for.
     */
    private static String esc(final String s) {
        return (s == null) ? "" : android.text.TextUtils.htmlEncode(s);
    }

    /**
     * Build the value of a {@code note:} href: percent-encode the path first (so the
     * Uri.decode() in handleUrl() reverses it exactly), then HTML-escape the result so the
     * attribute itself cannot be terminated early. Slashes are left readable.
     */
    private static String escHref(final String absolutePath) {
        if (absolutePath == null) {
            return "";
        }
        return esc(android.net.Uri.encode(absolutePath, "/"));
    }

    private boolean hasAnyContentBeyondSeed(File f) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            Log.w(TAG, "hasAnyContentBeyondSeed failed for " + f, e);
            return false;
        } finally {
            try { if (br != null) br.close(); } catch (Exception ignore) {}
        }
    }
}
