// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.VisualSelection;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.BlockSelection.LineRange;

/**
 * How Neovim's visual selections are shown, without a widget: the caret is a {@link FakeCaret} and
 * the block highlight a fake that keeps the ranges it was given. That the real {@code StyledText}
 * caret and the painted overlay do the same is tested in the UI tests ({@code StyledTextCaretTest},
 * {@code BlockOverlayTest}).
 *
 * <p>Offsets in the text {@code "alpha\nbeta\ngamma"}: line 1 is 0-5, line 2 starts at 6, line 3 at 11.
 */
class VisualSelectionViewTest {

    private static final String TEXT = "alpha\nbeta\ngamma";
    /** {@code Ctrl-V}, the mode Neovim reports for a blockwise selection. */
    private static final String BLOCKWISE = "\u0016";

    /** Records what would be painted. */
    private static final class FakeHighlight implements BlockHighlight {
        List<LineRange> ranges = List.of();
        boolean disposed;

        @Override
        public void show(IDocument doc, List<LineRange> lineRanges) {
            ranges = List.copyOf(lineRanges);
        }

        @Override
        public void clear() {
            ranges = List.of();
        }

        @Override
        public void dispose() {
            disposed = true;
        }
    }

    private final IDocument doc = new Document(TEXT);
    private final FakeCaret caret = new FakeCaret(doc);
    private final FakeHighlight highlight = new FakeHighlight();
    /** One entry per caret change the view marked as coming from Neovim (so it is not echoed back). */
    private final List<String> caretChanges = new ArrayList<>();
    private final VisualSelectionView view = new VisualSelectionView(caret, highlight, change -> {
        caretChanges.add("from Neovim");
        change.run();
    });

    @Test
    void characterwiseSelectionIncludesTheCharacterUnderTheCursor() {
        // v from "lpha" (line 1, col 1) to "be" (line 2, col 1): offsets 1 to 8, the "e" included
        view.show(doc, new VisualSelection(true, "v", 1, 1, 2, 1));
        caret.assertSelectionRange(1, 7);
    }

    @Test
    void cornersInEitherOrderGiveTheSameSelection() {
        view.show(doc, new VisualSelection(true, "v", 2, 1, 1, 1));
        caret.assertSelectionRange(1, 7);
    }

    @Test
    void linewiseSelectionCoversWholeLines() {
        view.show(doc, new VisualSelection(true, "V", 2, 2, 3, 0));
        caret.assertSelectionRange(6, 10); // "beta\ngamma"
    }

    @Test
    void linewiseSelectionUpwardsCoversWholeLines() {
        // V on "gamma", then k: the anchor (line 3) comes before the cursor (line 2)
        view.show(doc, new VisualSelection(true, "V", 3, 0, 2, 2));
        caret.assertSelectionRange(6, 10);
    }

    @Test
    void theCaretIsAtTheCursorEndAndNotEchoedToNeovim() {
        view.show(doc, new VisualSelection(true, "v", 1, 1, 2, 1)); // cursor below the anchor
        assertEquals(8, caret.caretOffset());
        view.show(doc, new VisualSelection(true, "v", 2, 1, 1, 1)); // after "o": cursor above
        caret.assertSelectionRange(1, 7);
        assertEquals(1, caret.caretOffset());
        assertEquals(List.of("from Neovim", "from Neovim"), caretChanges);
    }

    @Test
    void inactiveSelectionClearsTheEditorSelection() {
        caret.place(5, 0, 5); // "alpha" selected
        view.show(doc, VisualSelection.none());
        caret.assertSelectionRange(5, 0); // nothing selected, the caret stays at the end
    }

    @Test
    void blockwiseSelectionMovesOnlyTheCaretAndMarksItAsNeovims() {
        caret.place(5, 0, 5);
        view.show(doc, new VisualSelection(true, BLOCKWISE, 1, 1, 3, 2));
        assertEquals(0, caret.selectionLength());
        caret.assertCaretPosition(3, 2);
        assertEquals(List.of("from Neovim"), caretChanges);
        assertEquals(3, highlight.ranges.size()); // one range per line
    }

    @Test
    void leavingBlockwiseRemovesTheHighlight() {
        view.show(doc, new VisualSelection(true, BLOCKWISE, 1, 1, 3, 2));
        view.show(doc, VisualSelection.none());
        assertEquals(List.of(), highlight.ranges);
    }

    @Test
    void clearBlockAndDisposeReachTheHighlight() {
        view.show(doc, new VisualSelection(true, BLOCKWISE, 1, 1, 3, 2));
        view.clearBlock(); // switching to Eclipse mode
        assertEquals(List.of(), highlight.ranges);
        view.dispose();
        assertEquals(true, highlight.disposed);
    }
}
