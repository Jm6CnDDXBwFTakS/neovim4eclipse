// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Listener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;

/**
 * Clicks in the editor's text through the operating system ({@link RealInput}), as a mouse makes
 * them: the synthetic events of the other tests reach the widget straight, past the system's
 * focus and activation handling. In Normal mode a left click is Neovim's and the editor keeps the
 * keyboard; Ctrl+click stays Eclipse's (USER_GUIDE.md, "Mouse"), Cmd+click on macOS, where that
 * is Eclipse's hyperlink gesture. Skipped where the system does not move the pointer (a Wayland
 * session), and on macOS unless the app running the tests has the Accessibility permission
 * ({@link RealInput#requireFront}). On Windows, a real X11 desktop and macOS the real pointer
 * moves during the test.
 */
class RealMouseEndToEndTest extends EndToEndTestBase {

    @AfterEach
    void forgetTheMapping() {
        SessionProbe.lua(session, "pcall(vim.keymap.del, 'n', '<C-LeftMouse>') pcall(vim.keymap.del, 'n', '<LeftMouse>')"
                + " vim.g.n4e_ctrl_click = nil return 0");
    }

    /** The point a click aims at for column {@code col} of line {@code line} (0-based): just inside that cell's left edge, halfway down. */
    private Point cell(int line, int col) {
        Point at = widget.getLocationAtOffset(widget.getOffsetAtLine(line) + col);
        return new Point(at.x + 1, at.y + widget.getLineHeight() / 2);
    }

    private List<?> neovimCursor() {
        return (List<?>) SessionProbe.lua(session, "return vim.api.nvim_win_get_cursor(0)");
    }

    /**
     * A real left click on gamma's line, its third column, in Normal mode: Neovim's cursor and the
     * caret land there, and the editor keeps the keyboard (no focus lost to the click's handling).
     */
    @Test
    void aRealClickInTheTextMovesTheCursorThereAndTheEditorKeepsTheKeyboard() {
        settle();
        RealInput.requireFront(widget, TIMEOUT_MS);
        Point at = cell(2, 2);
        assumeTrue(RealInput.leftClick(widget, at.x, at.y, false), "the system did not move the pointer (a Wayland session)");
        waitUntil("Neovim's cursor on gamma's third column", TIMEOUT_MS, () -> {
            settle();
            return List.of(3L, 2L).equals(neovimCursor().stream().map(n -> ((Number) n).longValue()).toList());
        }, () -> "; Neovim's cursor " + neovimCursor());
        waitUntil("the caret there too", TIMEOUT_MS, () -> {
            settle();
            return widget.getCaretOffset() == widget.getOffsetAtLine(2) + 2;
        }, () -> "; caret at " + widget.getCaretOffset());
        assertTrue(widget.isFocusControl(), "the editor keeps the keyboard");
        assertEquals("n", session.getMode());
    }

    /**
     * A real Ctrl+click in the text (Cmd+click on macOS, Eclipse's M1) goes to Eclipse (its
     * hyperlinks), not to Neovim: mappings of
     * the test's own on {@code <C-LeftMouse>} and on {@code <LeftMouse>} never run (the plug-in
     * sends Neovim a click without Ctrl, so a Ctrl+click that reached Neovim would come as a plain
     * {@code <LeftMouse>}, and the first mapping alone could not see it). The check waits until the
     * widget has seen the click's release (a display filter), then until Neovim has handled
     * everything sent so far ({@link #settle}): a click sent on to Neovim would have reached it by
     * then.
     */
    @Test
    void aRealCtrlClickStaysEclipses() {
        settle();
        SessionProbe.lua(session, "vim.keymap.set('n', '<C-LeftMouse>', function() vim.g.n4e_ctrl_click = 'C-LeftMouse' end)"
                + " vim.keymap.set('n', '<LeftMouse>', function() vim.g.n4e_ctrl_click = 'LeftMouse' end) return 0");
        settle();
        RealInput.requireFront(widget, TIMEOUT_MS);
        Point at = cell(2, 2);
        boolean[] released = new boolean[1];
        Listener seen = e -> released[0] |= e.widget == widget && (e.stateMask & SWT.MOD1) != 0;
        widget.getDisplay().addFilter(SWT.MouseUp, seen);
        try {
            assumeTrue(RealInput.leftClick(widget, at.x, at.y, true), "the system did not move the pointer (a Wayland session)");
            waitUntil("the M1+click's release reached the editor", TIMEOUT_MS, () -> {
                settle();
                return released[0];
            });
        } finally {
            widget.getDisplay().removeFilter(SWT.MouseUp, seen);
        }
        settle();
        assertEquals(null, SessionProbe.lua(session, "return vim.g.n4e_ctrl_click"), "Neovim got no click, with Ctrl or without");
        assertTrue(widget.isFocusControl(), "the editor keeps the keyboard");
    }
}
