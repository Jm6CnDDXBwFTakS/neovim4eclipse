// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.text.Document;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.MouseInput;

/**
 * The left mouse button handed to Neovim, on a real {@link StyledText}: which events become
 * {@link MouseInput}s, at which document position, and that the widget then does not act on them
 * itself. Neovim is a list that records the events; what Neovim does with them is tested in
 * {@code NeovimSessionIntegrationTest}.
 */
class NeovimMouseTest {

    private Shell shell;
    private StyledText widget;
    private final Document document = new Document("alpha beta\n\nlast line");
    private final List<String> sent = new ArrayList<>();
    private boolean forward = true;
    private int presses;
    /** What reached the widget's own mouse listeners (they come after the filter). */
    private final List<Integer> widgetSaw = new ArrayList<>();
    private NeovimMouse mouse;

    @BeforeEach
    void create() {
        shell = new Shell(Display.getDefault());
        shell.setSize(400, 300);
        widget = new StyledText(shell, SWT.MULTI);
        widget.setBounds(0, 0, 400, 300);
        widget.setText(document.get());
        shell.open();
        for (int type : new int[] { SWT.MouseDown, SWT.MouseMove, SWT.MouseUp, SWT.MouseDoubleClick }) {
            widget.addListener(type, e -> widgetSaw.add(e.type));
        }
        mouse = new NeovimMouse(widget, offset -> offset, () -> document, () -> forward,
                e -> sent.add(e.action().luaName() + " " + e.line1() + ":" + e.col0() + e.modifiers()), () -> presses++);
    }

    @AfterEach
    void dispose() {
        mouse.dispose();
        shell.dispose();
    }

    /** A point on the character at document {@code offset} (its left half). */
    private Point on(int offset) {
        Point p = widget.getLocationAtOffset(offset);
        return new Point(p.x + 1, p.y + widget.getLineHeight() / 2);
    }

    private void event(int type, Point at, int button, int stateMask) {
        Event e = new Event();
        e.x = at.x;
        e.y = at.y;
        e.button = button;
        e.stateMask = stateMask;
        e.count = 1;
        widget.notifyListeners(type, e);
    }

    @Test
    void aClickGoesToNeovimAndNotToTheWidget() {
        event(SWT.MouseDown, on(6), 1, 0); // the b of beta
        event(SWT.MouseUp, on(6), 1, SWT.BUTTON1);

        assertEquals(List.of("press 1:6", "release 1:6"), sent);
        assertEquals(List.of(), widgetSaw, "the widget neither moves its caret nor selects");
        assertEquals(1, presses);
    }

    @Test
    void aDragSendsEachNewCharacterOnce() {
        event(SWT.MouseDown, on(0), 1, 0);
        event(SWT.MouseMove, on(1), 0, SWT.BUTTON1);
        event(SWT.MouseMove, on(1), 0, SWT.BUTTON1); // still on the l
        event(SWT.MouseMove, on(13), 0, SWT.BUTTON1); // line 3, "last line": the s
        event(SWT.MouseUp, on(13), 1, SWT.BUTTON1);

        assertEquals(List.of("press 1:0", "drag 1:1", "drag 3:1", "release 3:1"), sent);
    }

    @Test
    void shiftAndAltAreSentAsNeovimsModifiers() {
        event(SWT.MouseDown, on(2), 1, SWT.SHIFT);
        event(SWT.MouseUp, on(2), 1, SWT.SHIFT | SWT.ALT);

        assertEquals(List.of("press 1:2S", "release 1:2SA"), sent);
    }

    /** Ctrl+click (Cmd+click on macOS) is Eclipse's hyperlink; the right and middle buttons are Eclipse's too. */
    @Test
    void hyperlinkClicksAndOtherButtonsStayEclipses() {
        event(SWT.MouseDown, on(2), 1, SWT.MOD1);
        event(SWT.MouseUp, on(2), 1, SWT.MOD1);
        event(SWT.MouseDown, on(2), 3, 0);
        event(SWT.MouseDown, on(2), 2, 0);

        assertEquals(List.of(), sent);
        assertTrue(widgetSaw.contains(SWT.MouseDown));
    }

    @Test
    void movesWithoutAButtonStayEclipses() {
        event(SWT.MouseMove, on(3), 0, 0); // a hover

        assertEquals(List.of(), sent);
        assertEquals(List.of(SWT.MouseMove), widgetSaw);
    }

    /** Insert mode or Eclipse mode: the editor handles the mouse as without Neovim. */
    @Test
    void whenNeovimDoesNotHandleTheMouseTheWidgetDoes() {
        forward = false;
        event(SWT.MouseDown, on(2), 1, 0);
        event(SWT.MouseUp, on(2), 1, SWT.BUTTON1);

        assertEquals(List.of(), sent);
        assertEquals(List.of(SWT.MouseDown, SWT.MouseUp), widgetSaw);
    }

    @Test
    void theDoubleClickEventIsSwallowedTooNeovimCountsThePresses() {
        event(SWT.MouseDoubleClick, on(2), 1, 0);

        assertEquals(List.of(), widgetSaw);
    }

    // ---- the character under the pointer ----

    @Test
    void theRightHalfOfACharacterIsStillThatCharacter() {
        Point a = widget.getLocationAtOffset(0);
        Point l = widget.getLocationAtOffset(1);
        int rightHalf = l.x - 1;
        assertEquals(0, NeovimMouse.widgetOffsetAt(widget, rightHalf, a.y + 1));
    }

    @Test
    void rightOfTheTextIsTheLinesEnd() {
        Point a = widget.getLocationAtOffset(0);
        assertEquals(10, NeovimMouse.widgetOffsetAt(widget, 390, a.y + 1), "past \"alpha beta\"");
    }

    @Test
    void anEmptyLineAndBelowTheText() {
        int emptyLineY = widget.getLinePixel(1) + 1;
        assertEquals(11, NeovimMouse.widgetOffsetAt(widget, 50, emptyLineY));
        assertEquals(document.getLength(), NeovimMouse.widgetOffsetAt(widget, 390, 290),
                "below the last line: that line's end");
    }
}
