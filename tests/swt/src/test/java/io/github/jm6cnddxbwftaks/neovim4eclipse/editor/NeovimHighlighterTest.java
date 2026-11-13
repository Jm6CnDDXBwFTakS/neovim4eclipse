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
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
