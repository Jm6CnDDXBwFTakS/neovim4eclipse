// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link StyledTextCaret} on a real widget (no folding). Besides the offsets, it pins down the SWT
 * behavior that {@link FakeCaret} copies: a caret move made by code notifies the caret listener
 * just like one made by the user.
 */
class StyledTextCaretTest {

    private Shell shell;
    private StyledText widget;
    private StyledTextCaret caret;
    private final List<String> moves = new ArrayList<>();
    private final Runnable listener = () -> moves.add("moved");

    @BeforeEach
    void create() {
        shell = new Shell(Display.getCurrent());
        widget = new StyledText(shell, SWT.MULTI);
        widget.setText("alpha\nbeta");
        caret = new StyledTextCaret(widget, new WidgetOffsets(widget, null));
        caret.addMoveListener(listener);
    }

    @AfterEach
    void dispose() {
        shell.dispose();
    }

    @Test
    void moveCaretMovesTheWidgetsCaretAndNotifies() {
        assertTrue(caret.moveCaret(7));
        assertEquals(7, widget.getCaretOffset());
        assertEquals(7, caret.caretOffset());
        assertEquals(List.of("moved"), moves);
    }

    @Test
    void moveCaretClearsTheSelection() {
        widget.setSelection(0, 5);
        caret.moveCaret(2);
        assertEquals(0, caret.selectionLength());
        assertEquals(2, caret.selectionStart());
    }

    @Test
    void aSelectionInEitherDirectionHasItsStartAndLength() {
        widget.setSelection(2, 5);
        assertEquals(2, caret.selectionStart());
        assertEquals(3, caret.selectionLength());
        widget.setSelection(5, 2); // caret at the start
        assertEquals(2, caret.selectionStart());
        assertEquals(3, caret.selectionLength());
        assertEquals(2, caret.caretOffset());
    }

    @Test
    void withoutASelectionTheStartIsTheCaret() {
        widget.setCaretOffset(4);
        assertEquals(4, caret.selectionStart());
        assertEquals(0, caret.selectionLength());
    }

    @Test
    void selectPutsTheCaretAtTheCaretEndInEitherDirection() {
        assertTrue(caret.select(1, 8)); // made downward
        assertEquals(new Point(1, 8), widget.getSelection());
        assertEquals(8, widget.getCaretOffset());
        assertTrue(caret.select(8, 1)); // made upward, as after Vim's o
        assertEquals(new Point(1, 8), widget.getSelection());
        assertEquals(1, widget.getCaretOffset());
        assertEquals(1, caret.selectionStart());
        assertEquals(7, caret.selectionLength());
    }

    @Test
    void selectingAnEmptyRangeClearsTheSelection() {
        widget.setSelection(0, 5);
        caret.select(3, 3);
        assertEquals(0, caret.selectionLength());
        assertEquals(3, caret.caretOffset());
    }

    @Test
    void aRemovedListenerHearsNothing() {
        caret.removeMoveListener(listener);
        caret.moveCaret(3);
        assertEquals(List.of(), moves);
    }
}
