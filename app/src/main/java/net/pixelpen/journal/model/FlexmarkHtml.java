/*#######################################################
 *
 *   Autumn - journal, archive, timeline
 *   Maintained 2026 by PIXEL-PEN
 *   License: Apache 2.0
 *
#########################################################*/
package net.pixelpen.journal.model;

import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughSubscriptExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.builder.Extension;
import com.vladsch.flexmark.util.options.MutableDataSet;

import java.util.Arrays;

/**
 * Markdown to an HTML fragment, for the exports.
 *
 * <p>The extensions are the ones that change how ordinary writing reads: tables, strikethrough
 * and task lists. The in-app viewer carries more (maths, emoji, tables of contents) for pages
 * that are being read inside the app; a note exported for a desktop reader gains nothing from
 * a script-driven maths renderer, and none of them needs a network.
 *
 * <p>One instance per export, on one thread: the parser and renderer are built once and reused
 * for every note.
 */
public final class FlexmarkHtml implements ArchiveHtmlExport.Markdown {

    private final Parser _parser;
    private final HtmlRenderer _renderer;

    public FlexmarkHtml() {
        final MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Arrays.<Extension>asList(
                TablesExtension.create(),
                StrikethroughSubscriptExtension.create(),
                TaskListExtension.create()));
        _parser = Parser.builder(options).build();
        _renderer = HtmlRenderer.builder(options).build();
    }

    @Override
    public String toHtml(final String markdown) {
        return _renderer.render(_parser.parse(markdown));
    }
}
