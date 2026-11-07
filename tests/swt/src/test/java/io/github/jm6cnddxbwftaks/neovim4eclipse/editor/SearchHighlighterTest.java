// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.source.SourceViewerConfiguration;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.SearchMatch;

/**
 * {@link SearchHighlighter} on a real source viewer, configured as an editor's is (a presentation
 * reconciler, which builds what the widget shows): the matches become backgrounds in the widget's
 * style ranges, the one under the caret in the stronger color, and nothing of the text's own
 * colors is lost.
 */
class SearchHighlighterTest {

    private static final Color MATCH = new Color(206, 204, 247);
    private static final Color CURRENT = new Color(155, 153, 185);

    private Shell shell;
    private SourceViewer viewer;
    private StyledText widget;
    private SearchHighlighter highlighter;

    @BeforeEach
    void create() {
        shell = new Shell(Display.getDefault());
        viewer = new SourceViewer(shell, null, SWT.MULTI);
        viewer.configure(new SourceViewerConfiguration());
        viewer.setDocument(new Document("a foo b\né foo\nnone"));
        widget = viewer.getTextWidget();
        widget.setSize(400, 200);
        highlighter = new SearchHighlighter(viewer, MATCH, CURRENT);
    }

    @AfterEach
    void dispose() {
        shell.dispose();
    }

    /** The background at document offset {@code offset}, or null. */
    private Color backgroundAt(int offset) {
        StyleRange range = widget.getStyleRangeAtOffset(offset);
        return range == null ? null : range.background;
    }

    @Test
    void neovimsByteColumnsBecomeDocumentOffsets() {
        highlighter.show(List.of(new SearchMatch(1, 2, 1, 5), new SearchMatch(2, 3, 2, 6)));
        // Line 2 starts at 8; "é " is three bytes but two characters, so foo is at 10.
        assertEquals(List.of(new Region(2, 3), new Region(10, 3)), highlighter.regions());
    }

    @Test
    void theMatchesArePaintedBehindTheText() {
        widget.setCaretOffset(widget.getCharCount()); // on no match
        highlighter.show(List.of(new SearchMatch(1, 2, 1, 5), new SearchMatch(2, 3, 2, 6)));
        assertEquals(MATCH, backgroundAt(2));
        assertEquals(MATCH, backgroundAt(4));
        assertNull(backgroundAt(5), "the end is exclusive");
        assertEquals(MATCH, backgroundAt(10));
        assertNull(backgroundAt(0));
    }

    @Test
    void theMatchUnderTheCaretIsTheCurrentOne() {
        widget.setCaretOffset(10);
        highlighter.show(List.of(new SearchMatch(1, 2, 1, 5), new SearchMatch(2, 3, 2, 6)));
        assertEquals(1, highlighter.current());
        assertEquals(CURRENT, backgroundAt(10));
        assertEquals(MATCH, backgroundAt(2));
        widget.setCaretOffset(2); // n
        assertEquals(0, highlighter.current());
        assertEquals(CURRENT, backgroundAt(2));
        assertEquals(MATCH, backgroundAt(10));
    }

    @Test
    void clearAndDisposeTakeTheBackgroundsAway() {
        highlighter.show(List.of(new SearchMatch(1, 2, 1, 5)));
        highlighter.clear();
        assertTrue(highlighter.regions().isEmpty());
        assertNull(backgroundAt(2));
        highlighter.show(List.of(new SearchMatch(1, 2, 1, 5)));
        highlighter.dispose();
        assertNull(backgroundAt(2));
    }

    @Test
    void aMatchAcrossLinesCoversTheLineBreak() {
        highlighter.show(List.of(new SearchMatch(1, 6, 2, 2))); // to after é, two bytes
        assertEquals(List.of(new Region(6, 3)), highlighter.regions(), "b, the line break and é");
    }

    /** A match the document no longer has (it changed since Neovim looked) is left out, not moved. */
    @Test
    void aMatchPastTheDocumentIsLeftOut() {
        highlighter.show(List.of(new SearchMatch(9, 0, 9, 3), new SearchMatch(1, 2, 1, 5)));
        assertEquals(List.of(new Region(2, 3)), highlighter.regions());
    }

    @Test
    void theRegionHoldingTheOffsetIsFound() {
        List<IRegion> regions = List.of(new Region(2, 3), new Region(10, 3));
        assertEquals(0, SearchHighlighter.indexAt(regions, 2));
        assertEquals(0, SearchHighlighter.indexAt(regions, 4));
        assertEquals(-1, SearchHighlighter.indexAt(regions, 5), "just after the end");
        assertEquals(1, SearchHighlighter.indexAt(regions, 12));
        assertEquals(-1, SearchHighlighter.indexAt(List.of(), 0));
    }
}
