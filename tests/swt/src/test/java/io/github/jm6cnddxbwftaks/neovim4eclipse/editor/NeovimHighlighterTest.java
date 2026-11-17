// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.TextPresentation;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.source.SourceViewerConfiguration;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.JumpLabels;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimHighlight;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.SearchMatch;

/**
 * {@link NeovimHighlighter} on a real source viewer, configured as an editor's is: Neovim's
 * highlights become backgrounds (and text colors, where the group sets one) in the widget's style
 * ranges, the last one on top, over a search match, and they follow an edit made in Eclipse.
 */
class NeovimHighlighterTest {

    private static final int YELLOW = 0xFCE094;
    private static final int DARK = 0x07080D;
    private static final int RED = 0xAA2222;
    private static final Color SEARCH = new Color(255, 214, 102);

    private Shell shell;
    private SourceViewer viewer;
    private StyledText widget;
    private SearchHighlighter search;
    private NeovimHighlighter highlighter;

    @BeforeEach
    void create() {
        shell = new Shell(Display.getDefault());
        viewer = new SourceViewer(shell, null, SWT.MULTI);
        viewer.configure(new SourceViewerConfiguration());
        viewer.setDocument(new Document("a foo b\né foo\nnone"));
        widget = viewer.getTextWidget();
        widget.setSize(400, 200);
        widget.setCaretOffset(widget.getCharCount());
        // In the editor's order: the search highlighter first, so these lie over its matches.
        search = new SearchHighlighter(viewer, SEARCH, SEARCH);
        highlighter = new NeovimHighlighter(viewer);
    }

    @AfterEach
    void dispose() {
        shell.dispose();
    }

    private StyleRange at(int offset) {
        return widget.getStyleRangeAtOffset(offset);
    }

    private static Color rgb(int value) {
        return new Color((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF);
    }

    @Test
    void aGroupWithATextColorPaintsBoth() {
        highlighter.show(List.of(new NeovimHighlight(new SearchMatch(2, 3, 2, 6), YELLOW, DARK)));
        // Line 2 starts at 8; "é " is three bytes but two characters, so foo is at 10.
        assertEquals(rgb(YELLOW), at(10).background);
        assertEquals(rgb(DARK), at(10).foreground, "the group's text color, readable on its background");
        assertNull(at(13), "the end is exclusive");
    }

    @Test
    void aGroupWithABackgroundAloneKeepsTheTextColor() {
        highlighter.show(List.of(new NeovimHighlight(new SearchMatch(1, 2, 1, 5), RED, NeovimHighlight.NO_COLOR)));
        assertEquals(rgb(RED), at(2).background);
        assertNull(at(2).foreground, "the syntax color stays");
    }

    /** A jump plug-in's backdrop: a text color and no background; the search match under it keeps its background. */
    @Test
    void aGroupWithATextColorAloneKeepsTheBackground() {
        search.show(List.of(new SearchMatch(1, 2, 1, 5)));
        highlighter.show(List.of(new NeovimHighlight(new SearchMatch(1, 0, 1, 7), NeovimHighlight.NO_COLOR, RED)));
        assertEquals(rgb(RED), at(0).foreground);
        assertNull(at(0).background, "the editor's background stays");
        assertEquals(rgb(RED), at(2).foreground);
        assertEquals(SEARCH, at(2).background, "and so does a search match's");
        highlighter.show(List.of());
        assertNull(at(0), "the text has its own colors again");
    }

    /**
     * A label is painted over the character it names, one cell wide per character of its text,
     * and the document keeps its text. A later piece of the same label starts its cells further.
     */
    @Test
    void labelsArePaintedOverTheirCharacters() {
        JumpLabels.Label first = new JumpLabels.Label(1, 2, 0, "x", YELLOW, DARK, true);
        JumpLabels.Label second = new JumpLabels.Label(1, 2, 1, "yz", RED, JumpLabels.NO_COLOR, false);
        JumpLabels.Label accented = new JumpLabels.Label(2, 3, 0, "q", YELLOW, DARK, false); // after the two-byte e-acute and a space
        highlighter.show(List.of(), List.of(first, second, accented));
        List<JumpLabelPainter.Painted> painted = highlighter.paintedLabels();
        assertEquals(List.of(first, second, accented), painted.stream().map(JumpLabelPainter.Painted::label).toList());
        Point f = widget.getLocationAtOffset(2);
        Rectangle cell = painted.get(0).cell();
        assertEquals(new Point(f.x, f.y), new Point(cell.x, cell.y), "over the f of foo");
        assertEquals(widget.getLineHeight(2), cell.height);
        assertEquals(cell.x + cell.width, painted.get(1).cell().x, "the second piece begins one cell on");
        assertEquals(2 * cell.width, painted.get(1).cell().width, "and is two cells wide");
        Point second2 = widget.getLocationAtOffset(widget.getOffsetAtLine(1) + 2);
        assertEquals(new Point(second2.x, second2.y), new Point(painted.get(2).cell().x, painted.get(2).cell().y),
                "byte column 3 of the second line is its third character");
        assertEquals("a foo b\n\u00E9 foo\nnone", viewer.getDocument().get(), "the text is untouched");
        widget.redraw();
        widget.update(); // a real paint: the listener must not fail
    }

    /**
     * A label for a line the document does not have is not painted; the others are. A column past
     * the line's end is the line's end, where flash labels a match that ends a line.
     */
    @Test
    void aLabelWithoutAPlaceIsLeftOut() {
        JumpLabels.Label here = new JumpLabels.Label(3, 0, 0, "x", YELLOW, DARK, false);
        JumpLabels.Label atEnd = new JumpLabels.Label(3, 99, 0, "e", YELLOW, DARK, false);
        highlighter.show(List.of(), List.of(new JumpLabels.Label(9, 0, 0, "x", YELLOW, DARK, false), here, atEnd));
        List<JumpLabelPainter.Painted> painted = highlighter.paintedLabels();
        assertEquals(List.of(here, atEnd), painted.stream().map(JumpLabelPainter.Painted::label).toList());
        assertEquals(widget.getLocationAtOffset(widget.getCharCount()).x, painted.get(1).cell().x, "at the end of the last line");
        highlighter.clear();
        assertEquals(List.of(), highlighter.paintedLabels());
    }

    /**
     * A paint computes only the labels on lines in view (and one line beyond each end): a paint
     * runs at every caret blink. A label scrolled out of view is back once its line shows again.
     */
    @Test
    void aPaintLeavesOutLabelsOnLinesOutOfView() {
        viewer.getDocument().set("line\n".repeat(400));
        JumpLabels.Label top = new JumpLabels.Label(1, 0, 0, "t", YELLOW, DARK, false);
        JumpLabels.Label far = new JumpLabels.Label(300, 0, 0, "f", YELLOW, DARK, false);
        highlighter.show(List.of(), List.of(top, far));
        widget.setTopIndex(0);
        assertEquals(List.of(top, far), highlighter.paintedLabels().stream().map(JumpLabelPainter.Painted::label).toList());
        assertEquals(List.of(top), highlighter.paintedLabelsInView().stream().map(JumpLabelPainter.Painted::label).toList());
        widget.setTopIndex(295);
        assertEquals(List.of(far), highlighter.paintedLabelsInView().stream().map(JumpLabelPainter.Painted::label).toList());
        widget.redraw();
        widget.update(); // a real paint, scrolled: the listener must not fail
    }

    /** Lowest priority first: the last lies on top, and both lie over a search match. */
    @Test
    void theLastHighlightLiesOnTopOfTheOthersAndOfASearchMatch() {
        search.show(List.of(new SearchMatch(1, 2, 1, 5)));
        assertEquals(SEARCH, at(2).background);
        highlighter.show(List.of(new NeovimHighlight(new SearchMatch(1, 0, 1, 7), RED, NeovimHighlight.NO_COLOR),
                new NeovimHighlight(new SearchMatch(1, 2, 1, 5), YELLOW, DARK)));
        assertEquals(rgb(RED), at(0).background, "the match alone");
        assertEquals(rgb(YELLOW), at(3).background, "the flash over the match and the search match");
        assertEquals(rgb(RED), at(6).background);
    }

    @Test
    void anEclipseEditMovesTheHighlightsAndDropsOneItTouches() {
        highlighter.show(List.of(new NeovimHighlight(new SearchMatch(1, 2, 1, 5), RED, NeovimHighlight.NO_COLOR),
                new NeovimHighlight(new SearchMatch(2, 3, 2, 6), YELLOW, DARK)));
        viewer.getDocument().set(viewer.getDocument().get()); // the whole text replaced
        assertTrue(highlighter.shown().isEmpty(), "a change over every highlight drops them");
        highlighter.show(List.of(new NeovimHighlight(new SearchMatch(2, 3, 2, 6), YELLOW, DARK)));
        widget.replaceTextRange(0, 0, "xx");
        assertEquals(new Region(12, 3), highlighter.shown().get(0).region(), "moved with the text");
        assertEquals(rgb(YELLOW), at(12).background);
    }

    @Test
    void clearAndDisposeTakeThemAway() {
        highlighter.show(List.of(new NeovimHighlight(new SearchMatch(1, 2, 1, 5), RED, NeovimHighlight.NO_COLOR)));
        highlighter.clear();
        assertNull(at(2));
        highlighter.show(List.of(new NeovimHighlight(new SearchMatch(1, 2, 1, 5), RED, NeovimHighlight.NO_COLOR)));
        highlighter.dispose();
        assertNull(at(2), "repainted without them");
        TextPresentation presentation = new TextPresentation(new Region(0, 7), 1);
        highlighter.applyTextPresentation(presentation);
        assertTrue(presentation.isEmpty(), "nothing left to merge");
    }

    @Test
    void aRangeTheDocumentNoLongerHasIsLeftOut() {
        highlighter.show(List.of(new NeovimHighlight(new SearchMatch(9, 0, 9, 3), RED, NeovimHighlight.NO_COLOR),
                new NeovimHighlight(new SearchMatch(1, 2, 1, 5), RED, NeovimHighlight.NO_COLOR)));
        assertEquals(1, highlighter.shown().size());
    }
}
