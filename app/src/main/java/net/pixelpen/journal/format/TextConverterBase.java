/*#######################################################
 *
 *   Maintained 2018-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.pixelpen.journal.format;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.text.format.DateFormat;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import net.pixelpen.journal.ApplicationObject;
import net.pixelpen.journal.R;
import net.pixelpen.journal.model.AppSettings;
import net.pixelpen.journal.model.Document;
import net.gsantner.opoc.format.GsTextUtils;
import net.gsantner.opoc.util.GsContextUtils;
import net.gsantner.opoc.util.GsFileUtils;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.io.FileInputStream;
import android.util.Base64;

import other.de.stanetz.jpencconverter.JavaPasswordbasedCryption;

@SuppressWarnings("WeakerAccess")
public abstract class TextConverterBase {
    //########################
    //## HTML
    //########################
    protected static final String UTF_CHARSET = "utf-8";
    protected static final String CONTENT_TYPE_HTML = "text/html";
    protected static final String CONTENT_TYPE_PLAIN = "text/plain";

    protected static final String CSS_S = "<style type='text/css'>";
    protected static final String CSS_E = "</style>";
    protected static final String JS_S = "<script>";
    protected static final String JS_E = "</script>";

    protected static final String TOKEN_TEXT_DIRECTION = "{{ app.text_direction }}"; // this is either 'right' or 'left'
    protected static final String TOKEN_FONT = "{{ app.text_font }}";
    protected static final String TOKEN_BW_INVERSE_OF_THEME = "{{ app.token_bw_inverse_of_theme }}";
    protected static final String TOKEN_BW_INVERSE_OF_THEME_HEADER_UNDERLINE = "{{ app.token_headline_underline_inverse_of_theme }}";
    protected static final String TOKEN_COLOR_GREY_OF_THEME = "{{ app.token_color_grey_inverse_of_theme }}";
    protected static final String TOKEN_LINK_COLOR = "{{ app.token_link_color }}";
    protected static final String TOKEN_ACCENT_COLOR = "{{ app.token_accent_color }}";
    protected static final String TOKEN_TEXT_CONVERTER_CSS_CLASS = "{{ post.text_converter_name }}";
    protected static final String TOKEN_TEXT_CONVERTER_MAX_ZOOM_OUT_BY_DEFAULT = "{{ app.webview_max_zoom_out_by_default }}";
    protected static final String TOKEN_POST_TODAY_DATE = "{{ post.date_today }}";
    protected static final String TOKEN_POST_LANG = "{{ post.lang }}";
    protected static final String TOKEN_FILEURI_VIEWED_FILE = "{{ app.fileuri_viewed_file }}";

    protected static final String HTML_DOCTYPE = "<!DOCTYPE html>";
    protected static final String HTML001_HEAD_WITH_BASESTYLE = "<html lang='" + TOKEN_POST_LANG + "'><head>" + CSS_S + "html,body{padding:4px 8px 4px 8px;font-family:'" + TOKEN_FONT + "';}h1,h2,h3,h4,h5,h6{font-family:'sans-serif-condensed';}a{color: " + TOKEN_LINK_COLOR + ";text-decoration:underline;}img{height:auto;max-width:100%;max-height: 90vh;margin:auto;}" + CSS_E;
    protected static final String HTML002_HEAD_WITH_STYLE_LIGHT = CSS_S + "html,body{color:#303030;}blockquote{color:#73747d;}" + CSS_E;
    protected static final String HTML002_HEAD_WITH_STYLE_DARK = CSS_S + "html,body{color:#ffffff;background-color:#303030;}a:visited{color:#dddddd;}blockquote{color:#cccccc;}" + CSS_E;
    protected static final String HTML003_RIGHT_TO_LEFT = CSS_S + "body{text-align:" + TOKEN_TEXT_DIRECTION + ";direction:rtl;}" + CSS_E;
    protected static final String HTML004_HEAD_META_VIEWPORT_MOBILE = "<style>video, img { max-width: 100%; } pre { max-width: 100%; overflow: auto; } </style>";//"<meta name='viewport' content='width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no'>";
    protected static final String HTML100_PERCENT_IN_FILEPATH = "<base>" + JS_S + "var newbase = document.baseURI.split('%').join('%25'); document.querySelector('base').setAttribute('href', newbase);" + JS_E;
    protected static final String CSS_TABLE_STYLE = CSS_S + "table, th, td {  border: 1px solid " + TOKEN_BW_INVERSE_OF_THEME + "; border-collapse: collapse; border-spacing: 0; padding: 6px; }" + CSS_E;
    protected static final String CSS_BUTTON_STYLE_MATERIAL = CSS_S + "button:hover,button:active {filter: invert(1);} button { display: inline-block; box-sizing: border-box; border: none; border-radius: 4px; padding: 0 16px; min-width: 64px; height: 36px; font-family: 'Roboto'; font-size: 14px; font-weight: 500;  line-height: 36px; overflow: hidden; outline: none; vertical-align: middle; text-align: center; text-overflow: ellipsis; text-transform: uppercase; box-shadow: 0 3px 1px -2px rgba(0, 0, 0, 0.2), 0 2px 2px 0 rgba(0, 0, 0, 0.14), 0 1px 5px 0 rgba(0, 0, 0, 0.12); margin: 4px 4px 8px 0px;}   " + CSS_E;
    protected static final String CSS_BUTTON_STYLE_EMOJIBTN = CSS_S + " .emojibtn,.fa {font-size:250%; background: transparent; padding: 0px; min-width:0px;}   " + CSS_E;
    protected static final String CSS_CLASS_STICKY = CSS_S + " .sticky {position: sticky; display: inline-block; border: 0px solid " + TOKEN_BW_INVERSE_OF_THEME + ";} " + CSS_E;
    protected static final String CSS_CLASS_FLOAT = CSS_S + " .floatl {float: left;} .clear {clear:both;} " + CSS_E;

    // onPageLoaded_markor_private() invokes the user injected function onPageLoaded()
    protected static final String HTML500_BODY = "</head>\n<body class='" + TOKEN_TEXT_CONVERTER_CSS_CLASS + "' onload='onPageLoaded_markor_private();'>\n\n<!-- USER DOCUMENT CONTENT -->\n\n\n";
    //protected static final String HTML900_TO_TOP = "<a class='back_to_top'>&uarr;</a>"
    //        + CSS_S + ".back_to_top { position: fixed; bottom: 80px; right: 40px; z-index: 9999; width: 30px; height: 30px; text-align: center; line-height: 30px; background: #f5f5f5; color: #444; cursor: pointer; border-radius: 2px; display: none; } .back_to_top:hover { background: #e9ebec; } .back_to_top-show { display: block; }" +CSS_E
    //        + "<script>" + "(function() { 'use strict'; function trackScroll() { var scrolled = window.pageYOffset; var coords = document.documentElement.clientHeight; if (scrolled > coords) { goTopBtn.classList.add('back_to_top-show'); } if (scrolled < coords) { goTopBtn.classList.remove('back_to_top-show'); } } function backToTop() { if (window.pageYOffset > 0) { window.scrollBy(0, -80); setTimeout(backToTop, 0); } } var goTopBtn = document.querySelector('.back_to_top'); window.addEventListener('scroll', trackScroll); goTopBtn.addEventListener('click', backToTop); })();" + "</script>";
    protected static final String HTML990_BODY_END = "\n\n<!-- USER DOCUMENT CONTENT END -->\n\n</body></html>";

    protected static final String HTML_ON_PAGE_LOAD_S = "<script> function onPageLoaded_markor_private() {\n";
    protected static final String HTML_ON_PAGE_LOAD_E = "\nonPageLoaded(); }\n</script>";

    // protected static final String HTML_JQUERY_INCLUDE = "<script src='file:///android_asset/jquery/jquery-3.3.1.min.js'></script>"; // currently not bundled

    //########################
    //## Methods
    //########################
    protected final AppSettings _appSettings;

    public TextConverterBase() {
        _appSettings = ApplicationObject.settings();
    }

    /**
     * Convert markup to target format and show the result in a WebView
     *
     * @param document The document containing the contents
     * @param webView  The WebView content to be shown in
     * @return Copy of converted html
     */
    public String convertMarkupShowInWebView(
            final Document document,
            final String content,
            final Activity context,
            final WebView webView,
            final boolean lightMode,
            final boolean lineNum) {
        String html;
        try {
            html = convertMarkup(content, context, lightMode, lineNum, document.file);
        } catch (Exception e) {
            html = "Please report at project issue tracker: " + e;
        }

        String parent = document.file.getParent();
        if (parent == null) {
            parent = _appSettings.getNotebookDirectory().getAbsolutePath();
        }
// The document is written to a file and loaded by URL, rather than handed to
        // loadDataWithBaseURL.
        //
        // loadDataWithBaseURL with a non-http base gives the document an OPAQUE origin on
        // modern WebView, not the file:// origin the base URL suggests. A document with an
        // opaque origin may not load file:// subresources at all, so every local image in a
        // note failed -- and failed whether the src was relative or absolute, which is what
        // ruled out the path as the cause. The markdown was right, the file was on disk, the
        // link resolved correctly, and the picture still never appeared.
        //
        // Loading a real file gives a real file:// origin, which setAllowFileAccess(true)
        // already permits to read other file:// URLs.
        //
        // The preview lives in the app's cache, never in the archive -- the archive is the
        // thing this app exists to keep, and it should not collect render artefacts. A <base>
        // element pointed at the note's own folder makes relative links such as
        // ../../../Images/2026/x.jpg resolve into the archive regardless of where the HTML
        // itself sits, so nothing has to rewrite src attributes.
        final String baseHref = Uri.fromFile(new File(parent)).toString() + "/";
        final String baseTag = "<base href=\"" + baseHref + "\">";
        final int headAt = html.indexOf("<head>");
        final String linked = (headAt < 0)
                ? baseTag + html
                : html.substring(0, headAt + 6) + baseTag + html.substring(headAt + 6);
        final String doc = inlineLocalImages(linked, new File(parent));

        // Named by content hash, with older previews removed: a fresh URL whenever the note
        // changes, and no accumulation. Guards against the WebView showing a stale render.
        File preview = null;
        try {
            final File dir = new File(context.getCacheDir(), "preview");
            if (dir.isDirectory()) {
                final File[] stale = dir.listFiles();
                if (stale != null) {
                    for (final File old : stale) {
                        old.delete();
                    }
                }
            } else {
                dir.mkdirs();
            }
            final File out = new File(dir, "v" + Integer.toHexString(doc.hashCode()) + ".html");
            final OutputStreamWriter w =
                    new OutputStreamWriter(new FileOutputStream(out), UTF_CHARSET);
            try {
                w.write(doc);
            } finally {
                w.close();
            }
            preview = out;
        } catch (final Exception ignored) {
            // Cache unwritable: fall back below rather than showing nothing.
            preview = null;
        }

        if (preview != null) {
            webView.loadUrl(Uri.fromFile(preview).toString());
        } else {
            webView.loadDataWithBaseURL(baseHref, doc, getContentType(), UTF_CHARSET, null);
        }

        // Putting the night mode back, because the WebView just took it away.
        //
        // Bringing up a WebView resets this process's resource configuration to the system's,
        // which silently undoes a forced dark or forced light. It is why opening a note that
        // renders -- one with a picture in it -- left the rest of the app on the wrong palette
        // afterwards, while a note that never reached the WebView was harmless.
        _appSettings.applyAppTheme();

        // When TOKEN_TEXT_CONVERTER_MAX_ZOOM_OUT_BY_DEFAULT is contained in text zoom out as far possible
        // Notice: overViewMode / useWideViewPort work differently
        for (int i = (html.contains(TOKEN_TEXT_CONVERTER_MAX_ZOOM_OUT_BY_DEFAULT) ? 0 : 99); i < 30; i++) {
            webView.postDelayed(webView::zoomOut, 210 * (i < 5 ? 1 : (i < 10 ? 2 : (i < 15 ? 3 : (i < 20 ? 5 : 9)))));
        }

        return html;
    }

    /**
     * Convert markup text to target format
     *
     * @param markup    Markup text
     * @param context   Android Context
     * @param lightMode
     * @param lineNum
     * @return html as String
     */
    public abstract String convertMarkup(String markup, Context context, boolean lightMode, boolean lineNum, File file);

    protected String putContentIntoTemplate(Context context, String content, boolean isExportInLightMode, File file, String onLoadJs, String head) {
        final String contentLower = content.toLowerCase();
        boolean darkTheme = GsContextUtils.instance.isDarkModeEnabled(context) && !isExportInLightMode;
        String html = HTML_DOCTYPE + HTML001_HEAD_WITH_BASESTYLE.replace(TOKEN_POST_LANG, Locale.getDefault().getLanguage()) + (darkTheme ? HTML002_HEAD_WITH_STYLE_DARK : HTML002_HEAD_WITH_STYLE_LIGHT);
        if (isExportInLightMode) {
            html = html.replace("html,body{color:#303030;}", "html,body{color: black !important; background-color: white !important;}");
        }
        html += HTML004_HEAD_META_VIEWPORT_MOBILE + CSS_TABLE_STYLE + CSS_CLASS_FLOAT + CSS_BUTTON_STYLE_MATERIAL + CSS_BUTTON_STYLE_EMOJIBTN + CSS_CLASS_STICKY;
        if (_appSettings.isRenderRtl()) {
            html += HTML003_RIGHT_TO_LEFT;
        }

        html += head + _appSettings.getInjectedHeader();

        html += HTML_ON_PAGE_LOAD_S + onLoadJs + HTML_ON_PAGE_LOAD_E;

        // Add custom font css if font is a filepath, swap path with new font-family
        String font = _appSettings.getFontFamily();
        if (font.startsWith("/")) {
            html += CSS_S + "@font-face { font-family: customfont; src: url('file://" + font + "'); }" + CSS_E;
            font = "customfont";
        }

        // Remove duplicate style blocks
        html = html.replace(CSS_E + CSS_S, "").replace(CSS_E + "\n" + CSS_S, "");

        // Options based on filepath
        if (file != null) {
            if (file.getAbsolutePath().contains("%") || ((contentLower.contains(".nextcloud") || contentLower.contains(".owncloud")) && (contentLower.contains("%2") || contentLower.contains("%4")))) {
                html += HTML100_PERCENT_IN_FILEPATH;
            }
        }

        // Load content
        html += HTML500_BODY;
        html += _appSettings.getInjectedBody();
        html += content;
        html += HTML990_BODY_END;

        // Replace tokens
        html = html
                .replace(TOKEN_BW_INVERSE_OF_THEME, darkTheme ? "white" : "black")
                .replace(TOKEN_BW_INVERSE_OF_THEME_HEADER_UNDERLINE, darkTheme ? "#eaecef" : "#696969")
                .replace(TOKEN_COLOR_GREY_OF_THEME, darkTheme ? "#393939" : GsTextUtils.colorToHexString(ContextCompat.getColor(context, R.color.lighter_grey)))
                .replace(TOKEN_LINK_COLOR, _appSettings.getViewModeLinkColor())
                .replace(TOKEN_ACCENT_COLOR, GsTextUtils.colorToHexString(ContextCompat.getColor(context, R.color.accent)))
                .replace(TOKEN_TEXT_DIRECTION, _appSettings.isRenderRtl() ? "right" : "left")
                .replace(TOKEN_FONT, font)
                .replace(TOKEN_TEXT_CONVERTER_CSS_CLASS, "format-" + getClass().getSimpleName().toLowerCase().replace("textconverter", "").replace("converter", "") + " fileext-" + GsFileUtils.getFilenameExtension(file).replace(".", ""))
                .replace(TOKEN_POST_TODAY_DATE, DateFormat.getDateFormat(context).format(new Date()))
                .replace(TOKEN_FILEURI_VIEWED_FILE, (file != null ? Uri.fromFile(file.getAbsoluteFile()).toString() : "file:///dummy").replace("'", "\\'").replace("\"", "\\\""));

        return html;
    }

    protected String getContentType() {
        return CONTENT_TYPE_HTML;
    }

    public boolean isFileOutOfThisFormat(final @NonNull File file) {
        final String name = file.getName().toLowerCase().replace(JavaPasswordbasedCryption.DEFAULT_ENCRYPTION_EXTENSION, "").trim();
        final String extWithDot = GsFileUtils.getFilenameExtension(name);
        return isFileOutOfThisFormat(file, name, extWithDot);
    }

    protected abstract boolean isFileOutOfThisFormat(final File file, final String name, final String ext);

    private static final Pattern IMG_SRC_PATTERN =
            Pattern.compile("(?i)(<img\\s[^>]*?src\\s*=\\s*)([\"'])(.*?)\\2");

    /** 16 MB of originals per note. Past that the remaining pictures stay as links. */
    private static final long INLINE_BUDGET_BYTES = 16L * 1024L * 1024L;

    /**
     * Replace local image sources with data: URIs.
     *
     * <p>Not an optimisation -- the pictures do not appear otherwise. A WebView would not load
     * them from file:// however the src was written: not relative, not absolute, and not after
     * the document was given a genuine file:// origin by writing it out and loading it by URL.
     * Three different shapes of the same failure, each verified on the device.
     *
     * <p>A data: URI is part of the document rather than a subresource fetch, so there is no
     * load for the WebView to refuse. It also fixes archived HTML pages saved into the archive,
     * whose images failed for exactly the same reason.
     *
     * <p>Only local files are touched. http and https sources are left alone -- this app has no
     * network permission, so they were never going to load, and rewriting them would hide that
     * rather than fix it. Already-inlined data: URIs are left alone too.
     */
    private static String inlineLocalImages(final String html, final File noteDir) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        final Matcher m = IMG_SRC_PATTERN.matcher(html);
        final StringBuffer out = new StringBuffer(html.length());
        long budget = INLINE_BUDGET_BYTES;
        while (m.find()) {
            String replacement = m.group(0);
            final File f = localFileFor(m.group(3), noteDir);
            if (f != null && f.isFile() && f.length() > 0 && f.length() <= budget) {
                final String data = dataUri(f);
                if (data != null) {
                    budget -= f.length();
                    replacement = m.group(1) + m.group(2) + data + m.group(2);
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The file an img src points at, or null if it is not a local file. */
    private static File localFileFor(final String src, final File noteDir) {
        if (src == null) {
            return null;
        }
        final String s = src.trim();
        if (s.isEmpty() || s.startsWith("data:") || s.startsWith("http:")
                || s.startsWith("https:")) {
            return null;
        }
        String path;
        if (s.startsWith("file://")) {
            path = Uri.parse(s).getPath();
        } else {
            // Percent-escapes survive into the markup for a name with a space in it.
            path = Uri.decode(s);
        }
        if (path == null || path.isEmpty()) {
            return null;
        }
        final File f = path.startsWith("/") ? new File(path) : new File(noteDir, path);
        try {
            return f.getCanonicalFile();
        } catch (final Exception e) {
            return f;
        }
    }

    private static String dataUri(final File f) {
        FileInputStream in = null;
        try {
            final int size = (int) f.length();
            final byte[] bytes = new byte[size];
            in = new FileInputStream(f);
            int read = 0;
            while (read < size) {
                final int n = in.read(bytes, read, size - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            if (read < size) {
                return null;
            }
            return "data:" + mimeFor(f.getName()) + ";base64,"
                    + Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (final Throwable t) {
            // OutOfMemoryError included: a picture too large to inline leaves the link as it was
            // rather than taking the whole render down with it.
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (final Exception ignored) {
                }
            }
        }
    }

    private static String mimeFor(final String name) {
        final String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) {
            return "image/png";
        }
        if (n.endsWith(".gif")) {
            return "image/gif";
        }
        if (n.endsWith(".webp")) {
            return "image/webp";
        }
        if (n.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (n.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/jpeg";
    }
}
