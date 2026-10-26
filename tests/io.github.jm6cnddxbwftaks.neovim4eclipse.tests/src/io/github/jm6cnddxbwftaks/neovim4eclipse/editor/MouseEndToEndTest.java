// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;

/**
 * The mouse in a real editor ({@link NeovimMouse}): clicks go to Neovim's own mouse, and its cursor
 * and visual selection come back to the editor. The file is "alpha\nbeta\ngamma\n".
 */
class MouseEndToEndTest extends EndToEndTestBase {

    /** A point on the character at document {@code offset} (no folding in this editor). */
    private Point on(int offset) {
        Point p = widget.getLocationAtOffset(offset);
        return new Point(p.x + 1, p.y + widget.getLineHeight() / 2);
    }

    private void mouse(int type, Point at, int stateMask) {
        Event e = new Event();
        e.x = at.x;
        e.y = at.y;
        e.button = 1;
        e.stateMask = stateMask;
        widget.notifyListeners(type, e);
    }

    private void click(int offset, int times) {
        for (int i = 0; i < times; i++) {
            mouse(SWT.MouseDown, on(offset), 0);
            mouse(SWT.MouseUp, on(offset), SWT.BUTTON1);
        }
    }

    private String selected() {
        return widget.getSelectionText();
    }

    @Test
    void aClickMovesNeovimsCursor() {
        click(8, 1); // the t of beta
        waitUntil("Neovim's cursor on line 2", TIMEOUT_MS, () -> cursorLine() == 2);
        type(widget, "x");
        expectText("alpha\nbea\ngamma\n");
    }

    /**
     * Neovim counts the three presses as one triple click only if each comes within
     * {@code 'mousetime'} of the one before. The default, 500 ms, is too short on a loaded machine,
     * so the test allows 10 s, and sets the default back for the tests after it.
     */
    @Test
    void aTripleClickSelectsTheLineAndDDeletesIt() {
        SessionProbe.lua(session, "vim.o.mousetime = 10000");
        try {
            click(7, 3); // beta
            // Wait for linewise visual mode itself: the second click already selects the word "beta"
            // (charwise, mode v), so waiting only for the selection could end before Neovim had the third.
            waitUntil("the line selected", TIMEOUT_MS, () -> "V".equals(session.getMode()) && selected().startsWith("beta"),
                    () -> "; selected [" + selected() + "], mode " + session.getMode());
            type(widget, "d");
            expectText("alpha\ngamma\n");
        } finally {
            SessionProbe.lua(session, "vim.o.mousetime = 500");
        }
    }

    @Test
    void aDragSelectsAndYIsNeovimsYank() {
        mouse(SWT.MouseDown, on(1), 0); // the l of alpha
        mouse(SWT.MouseMove, on(8), SWT.BUTTON1); // the t of beta
        mouse(SWT.MouseUp, on(8), SWT.BUTTON1);
        waitUntil("the drag selected", TIMEOUT_MS, () -> selected().equals("lpha\nbet"),
                () -> "; selected [" + selected() + "], mode " + session.getMode());
        type(widget, "d");
        expectText("aa\ngamma\n");
    }

    /** With the {@code :} box open, a click in the editor abandons the line and then lands. */
    @Test
    void aClickWithTheBoxOpenClosesItAndMovesTheCursor() {
        type(widget, ':');
        waitUntil("the : box", TIMEOUT_MS, () -> textChildOf(widget) != null);
        UiTestSupport.requireKeyboardFocus(textChildOf(widget), TIMEOUT_MS);

        click(12, 1); // the m of gamma

        waitUntil("the box closed", TIMEOUT_MS, () -> textChildOf(widget) == null);
        waitUntil("Neovim's cursor on line 3", TIMEOUT_MS, () -> cursorLine() == 3,
                () -> "; mode " + session.getMode());
        type(widget, "x");
        expectText("alpha\nbeta\ngmma\n");
    }
}
