/*#######################################################
 *
 *   Autumn
 *   Copyright (C) 2026 PIXEL-PEN
 *   Licensed under the GNU General Public License v3.0
 *
#########################################################*/
package net.pixelpen.journal.activity;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Html;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import net.pixelpen.journal.R;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * The user guide: one scrolling screen, built from {@code assets/guide.md}.
 *
 * <p>Rendered natively rather than in a WebView. The guide is the one screen whose whole job
 * is to look like the rest of the app, and the markdown stylesheet does not -- it puts
 * headings in condensed type with a rule beneath, which is what made the quicknote page shout.
 * Native views also mean no renderer assets, no page load and no zoom controls on a document
 * nobody wants to zoom.
 *
 * <p>The markdown it understands is small on purpose: {@code #} and {@code ##} headings,
 * {@code >} callouts, {@code -} bullets, paragraphs, and inline {@code **bold**} and
 * {@code `code`}. Writing the guide stays a matter of editing a text file; if you need more
 * than this subset, the guide is probably getting too elaborate.
 */
public class GuideActivity extends AutumnBaseActivity {

    private static final String ASSET = "guide.md";

    @Override
    public void onCreate(final Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.guide__activity);

        final Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.guide);
        setSupportActionBar(toolbar);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_white_24dp);
        toolbar.setNavigationOnClickListener(v -> onBackPressed());

        render(findViewById(R.id.guide__container), readAsset());
    }

    private List<String> readAsset() {
        final List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(getAssets().open(ASSET), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                lines.add(line);
            }
        } catch (final IOException e) {
            lines.add("The guide could not be opened.");
        }
        return lines;
    }

    /**
     * Group the lines into blocks and give each one a view. A block ends at a blank line, or
     * when the next line starts a different kind of block -- which is what lets consecutive
     * bullets each become their own view while a wrapped paragraph stays one.
     */
    private void render(final LinearLayout container, final List<String> lines) {
        final StringBuilder para = new StringBuilder();
        final StringBuilder quote = new StringBuilder();
        for (final String raw : lines) {
            final String line = raw.trim();
            final boolean isQuote = line.startsWith("> ");

            // A wrapped paragraph is one view, and so is a wrapped callout. Both only end
            // when something else starts, which is why they are flushed here rather than
            // emitted line by line -- a two-line callout was becoming two stacked boxes.
            if (!isQuote) {
                flush(container, quote, true);
            }
            if (line.isEmpty() || line.startsWith("#") || isQuote || line.startsWith("- ")) {
                flush(container, para, false);
            }
            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("### ")) {
                container.addView(heading(line.substring(4), 3));
            } else if (line.startsWith("## ")) {
                container.addView(heading(line.substring(3), 2));
            } else if (line.startsWith("# ")) {
                container.addView(heading(line.substring(2), 1));
            } else if (isQuote) {
                append(quote, line.substring(2));
            } else if (line.startsWith("- ")) {
                container.addView(bullet(line.substring(2)));
            } else {
                append(para, line);
            }
        }
        flush(container, quote, true);
        flush(container, para, false);
    }

    private static void append(final StringBuilder sb, final String line) {
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(line);
    }

    private void flush(final LinearLayout container, final StringBuilder sb, final boolean isCallout) {
        if (sb.length() == 0) {
            return;
        }
        container.addView(isCallout ? callout(sb.toString()) : body(sb.toString()));
        sb.setLength(0);
    }

    // -- views -----------------------------------------------------------------------------

    private TextView base(final String text, final float sizeSp, final int colorRes) {
        final TextView t = new TextView(this);
        t.setText(Html.fromHtml(inline(text)));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(ContextCompat.getColor(this, colorRes));
        t.setLineSpacing(dp(4), 1f);
        return t;
    }

    /**
     * Levels 1 to 3. Three exists because the Year and Timeline sections each carry a part
     * that needs its own title without being promoted to a section of the archive in its own
     * right. Without it a "### " line fell through to the paragraph branch and was drawn as
     * body text with its hashes still attached.
     */
    private TextView heading(final String text, final int level) {
        final float size = level == 1 ? 24 : level == 2 ? 18 : 16.5f;
        final TextView t = base(text, size,
                level == 1 ? R.color.guide_title : R.color.guide_heading);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        margins(t, 0, level == 1 ? 8 : level == 2 ? 30 : 20, 0, level == 3 ? 6 : 10);
        return t;
    }

    private TextView body(final String text) {
        final TextView t = base(text, 16, R.color.guide_body);
        margins(t, 0, 0, 0, 12);
        return t;
    }

    private TextView bullet(final String text) {
        final TextView t = base("• " + text, 16, R.color.guide_body);
        margins(t, 10, 0, 0, 8);
        return t;
    }

    private TextView callout(final String text) {
        final TextView t = base(text, 16, R.color.guide_body);
        t.setBackgroundResource(R.drawable.bg_guide_callout);
        t.setPadding((int) dp(16), (int) dp(14), (int) dp(16), (int) dp(14));
        margins(t, 0, 6, 0, 16);
        return t;
    }

    // -- helpers ---------------------------------------------------------------------------

    /** The inline subset: bold and code. Escaped first, so the guide cannot inject markup. */
    private static String inline(final String s) {
        String out = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        out = out.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        out = out.replaceAll("`(.+?)`", "<tt>$1</tt>");
        return out;
    }

    private void margins(final TextView t, final int l, final int top, final int r, final int b) {
        final LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins((int) dp(l), (int) dp(top), (int) dp(r), (int) dp(b));
        t.setLayoutParams(p);
    }

    private float dp(final float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
