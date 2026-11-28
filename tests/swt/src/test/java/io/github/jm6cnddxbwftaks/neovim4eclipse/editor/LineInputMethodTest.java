// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Caret;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link LineInputMethod} on a real {@code StyledText}: while Neovim's command line is open, an
 * input method's events do not reach the text widget (the document stays as it was), the committed
 * text goes to Neovim's line once, its echo as a key press is dropped, and the composing text is
 * drawn at the line's cursor. The events are synthetic: a real input method needs a person
 * (docs/MANUAL_CHECKS.md, the {@code ime-*} cards).
 */
class LineInputMethodTest {

    private static final Rectangle CURSOR = new Rectangle(40, 100, 8, 16);

    private Shell shell;
    private StyledText widget;
    private final List<String> pasted = new ArrayList<>();
    private final List<Character> keysSeen = new ArrayList<>();
    private LineInputMethod line;

    @BeforeEach
    void create() {
        shell = new Shell(Display.getDefault());
        widget = new StyledText(shell, SWT.MULTI);
        widget.setSize(400, 200);
        widget.setText("the document");
        widget.addListener(SWT.KeyDown, e -> keysSeen.add(e.character));
        line = new LineInputMethod(widget, () -> CURSOR, pasted::add);
    }

    @AfterEach
    void dispose() {
        line.dispose();
        shell.dispose();
    }

    private Event composition(String text) {
        Event e = new Event();
        e.detail = SWT.COMPOSITION_CHANGED;
        e.text = text;
        e.doit = true;
        widget.notifyListeners(SWT.ImeComposition, e);
        return e;
    }

    private void key(char character) {
        Event e = new Event();
        e.character = character;
        e.doit = true;
        widget.notifyListeners(SWT.KeyDown, e);
    }

    /** With the line open, a composition goes no further than the filter: the document is untouched. */
    @Test
    void aCompositionDoesNotReachTheDocumentWhileTheLineIsOpen() {
        line.lineOpen(true);
        Event e = composition("か");
        assertEquals(SWT.None, e.type, "the filter took the event");
        assertTrue(e.doit, "not refused: on macOS a refused commit makes Cocoa send the raw Enter");
        assertEquals("the document", widget.getText());
        assertTrue(line.boxForTests().getVisible(), "the composing text is shown");
        assertEquals(CURSOR.x, line.boxForTests().getBounds().x, "at the line's cursor");
        assertEquals(CURSOR.y, line.boxForTests().getBounds().y);
    }

    /** The commit goes to Neovim's line once; the key press that repeats it is dropped, the next key is not. */
    @Test
    void theCommitIsPastedOnceAndItsEchoKeyDropped() {
        line.lineOpen(true);
        line.composed("可", 0);
        line.composed("可", 1);
        assertEquals(List.of("可"), pasted);
        assertFalse(line.boxForTests().getVisible(), "nothing composing after the commit");
        key('可');
        key('x');
        assertEquals(List.of('x'), keysSeen, "the echo dropped, the next key kept");
    }

    /** Closed, the line takes nothing: keys and the input method are the editor's again. */
    @Test
    void afterTheLineClosedNothingIsTaken() {
        line.lineOpen(true);
        line.composed("可", 1);
        line.lineOpen(false);
        key('可');
        assertEquals(List.of('可'), keysSeen, "the key reaches the editor");
        Event e = composition("か");
        assertEquals(SWT.ImeComposition, e.type, "the event goes on to the widget");
    }

    /** A click while composing ends the composition: nothing is sent, the box goes. */
    @Test
    void aClickEndsTheCompositionUnsent() {
        line.lineOpen(true);
        line.composed("か", 0);
        Event click = new Event();
        click.button = 1;
        widget.notifyListeners(SWT.MouseDown, click);
        assertEquals(List.of(), pasted);
        assertFalse(line.boxForTests().getVisible());
    }

    /**
     * The systems open the candidate list at the widget's caret: while composing, the caret sits at
     * the line's cursor; when the line closes it is back where it was.
     */
    @Test
    void theCaretFollowsTheLineWhileComposing() {
        Point before = widget.getCaret().getLocation();
        line.lineOpen(true);
        line.composed("か", 0);
        assertEquals(new Point(CURSOR.x, CURSOR.y), widget.getCaret().getLocation(), "at the line's cursor");
        line.composed("可", 1);
        line.lineOpen(false);
        assertEquals(before, widget.getCaret().getLocation(), "back after the line closed");
    }

    /**
     * In a float the widget has no caret ({@code CursorStyleController} hid Eclipse's): while
     * composing it has the stand-in, at the cursor's cell, where the systems open the candidate
     * list; at the commit it goes, and Eclipse's caret, back when the float closes, was not moved.
     */
    @Test
    void inAFloatTheStandInIsAtTheCursorWhileComposing() {
        CursorStyleController style = new CursorStyleController(widget);
        LineInputMethod inFloat = new LineInputMethod(widget, () -> CURSOR, pasted::add, style::composing);
        Caret eclipses = widget.getCaret();
        Point before = eclipses.getLocation();
        style.setHidden(true); // a float has the keys
        inFloat.lineOpen(true);
        inFloat.composed("か", 0);
        Caret standIn = widget.getCaret();
        assertNotNull(standIn, "a caret for the candidate list");
        assertNotSame(eclipses, standIn);
        assertEquals(new Point(CURSOR.x, CURSOR.y), standIn.getLocation(), "at the cursor's cell");
        inFloat.composed("可", 1); // committed: the composition is over
        assertNull(widget.getCaret(), "no caret again once nothing is composed");
        style.setHidden(false); // the float closes
        inFloat.lineOpen(false);
        assertSame(eclipses, widget.getCaret());
        assertEquals(before, eclipses.getLocation(), "Eclipse's caret was not moved");
        inFloat.dispose();
        style.dispose();
    }

    /**
     * Neovim's cursor cell not known yet (the float's canvas not made): no stand-in, which would
     * stay where StyledText put it, in the text, with no composing box over it.
     */
    @Test
    void withoutACursorCellAFloatGetsNoStandIn() {
        CursorStyleController style = new CursorStyleController(widget);
        LineInputMethod noCell = new LineInputMethod(widget, () -> null, pasted::add, style::composing);
        style.setHidden(true);
        noCell.lineOpen(true);
        noCell.composed("か", 0);
        assertNull(widget.getCaret(), "no caret in the text");
        noCell.lineOpen(false);
        noCell.dispose();
        style.dispose();
    }
}
