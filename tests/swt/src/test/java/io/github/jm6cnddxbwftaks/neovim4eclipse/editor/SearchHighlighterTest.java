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
 * style ranges, all in one color, the one under the caret gets a box around it, and nothing of the
 * text's own colors is lost.
 */
class SearchHighlighterTest {

    private static final Color MATCH = new Color(206, 204, 247);
    private static final Color BOX = new Color(103, 102, 124);

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
        highlighter = new SearchHighlighter(viewer, MATCH, BOX);
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

    /** The match under the caret keeps the match background, so its text stays readable, and gets a box. */
    @Test
    void theMatchUnderTheCaretGetsABox() {
        widget.setCaretOffset(10);
        highlighter.show(List.of(new SearchMatch(1, 2, 1, 5), new SearchMatch(2, 3, 2, 6)));
        assertEquals(1, highlighter.current());
        assertEquals(MATCH, backgroundAt(10), "the same background as the others");
        assertEquals(List.of(widget.getTextBounds(10, 12)), highlighter.boxes(), "a box around foo on line 2");
        widget.setCaretOffset(2); // n
        assertEquals(0, highlighter.current());
        assertEquals(List.of(widget.getTextBounds(2, 4)), highlighter.boxes());
        widget.setCaretOffset(widget.getCharCount());
        assertTrue(highlighter.boxes().isEmpty(), "no match under the caret: no box");
    }

    /** A current match across lines gets a box on each line it covers. */
    @Test
    void aMatchAcrossLinesGetsABoxPerLine() {
        widget.setCaretOffset(6);
        highlighter.show(List.of(new SearchMatch(1, 6, 2, 2)));
        assertEquals(List.of(widget.getTextBounds(6, 6), widget.getTextBounds(8, 8)), highlighter.boxes(),
                "b on line 1, é on line 2 (the line break has no box)");
    }

    /** The box is drawn over the match: a pixel of its top edge has the box color. */
    @Test
    void theBoxIsPainted() {
        widget.setCaretOffset(2);
        highlighter.show(List.of(new SearchMatch(1, 2, 1, 5)));
        org.eclipse.swt.graphics.Rectangle box = highlighter.boxes().get(0);
        org.eclipse.swt.graphics.Image image = new org.eclipse.swt.graphics.Image(widget.getDisplay(), 400, 200);
        try {
            org.eclipse.swt.graphics.GC gc = new org.eclipse.swt.graphics.GC(image);
            try {
                highlighter.paintBox(gc); // as the paint listener does, into an image (the shell is not shown)
            } finally {
                gc.dispose();
            }
            org.eclipse.swt.graphics.ImageData data = image.getImageData();
            assertEquals(BOX.getRGB(), data.palette.getRGB(data.getPixel(box.x + box.width / 2, box.y)));
        } finally {
            image.dispose();
        }
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

    /** Text typed before a match moves it; text typed in it drops it (Neovim has not matched the new text). */
    @Test
    void theMatchesFollowEclipsesEdits() throws Exception {
        highlighter.show(List.of(new SearchMatch(1, 2, 1, 5), new SearchMatch(2, 3, 2, 6)));
        viewer.getDocument().replace(0, 0, "xx"); // "xxa foo b\n..."
        assertEquals(List.of(new Region(4, 3), new Region(12, 3)), highlighter.regions());
        assertEquals(MATCH, backgroundAt(4), "painted where foo now is");
        assertNull(backgroundAt(2));
        viewer.getDocument().replace(5, 1, "O"); // inside the first foo
        assertEquals(List.of(new Region(12, 3)), highlighter.regions(), "the touched match is dropped");
        viewer.getDocument().replace(15, 0, "!"); // right after the second
        assertEquals(List.of(new Region(12, 3)), highlighter.regions(), "an edit after a match leaves it");
    }

    /** The editor reports the lines it shows, 1-based, at first and when it scrolls. */
    @Test
    void theLinesShownAreReported() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 200; i++) {
            text.append("line ").append(i).append('\n');
        }
        viewer.setDocument(new Document(text.toString()));
        java.util.List<int[]> reports = new java.util.ArrayList<>();
        SearchHighlighter fresh = new SearchHighlighter(viewer, MATCH, BOX);
        fresh.setViewListener((top, bottom) -> reports.add(new int[] { top, bottom }));
        assertEquals(1, reports.size(), "a first report");
        assertEquals(1, reports.get(0)[0]);
        assertEquals(viewer.getBottomIndex() + 1, reports.get(0)[1]);
        viewer.setTopIndex(99);
        assertEquals(100, reports.get(reports.size() - 1)[0], "after a scroll, from line 100");
        int count = reports.size();
        viewer.setTopIndex(99);
        assertEquals(count, reports.size(), "the same view is not reported twice");
        fresh.dispose();
    }

    /** With word wrap, the current match's part on each visual row gets a box of its own. */
    @Test
    void aWrappedMatchGetsABoxPerRow() {
        viewer.setDocument(new Document("aaaa bbbb cccc dddd eeee ffff"));
        widget.setWordWrap(true);
        widget.setSize(widget.computeSize(SWT.DEFAULT, SWT.DEFAULT).x / 2, 200); // about half the line per row
        widget.setCaretOffset(0);
        SearchHighlighter fresh = new SearchHighlighter(viewer, MATCH, BOX);
        fresh.show(List.of(new SearchMatch(1, 0, 1, 29)));
        List<org.eclipse.swt.graphics.Rectangle> boxes = fresh.boxes();
        assertTrue(boxes.size() > 1, "one box per row: " + boxes);
        for (org.eclipse.swt.graphics.Rectangle box : boxes) {
            assertEquals(widget.getLineHeight(), box.height, "each box one row high: " + boxes);
        }
        fresh.dispose();
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
