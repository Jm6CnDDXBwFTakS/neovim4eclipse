// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IPageLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;

/**
 * Neovim's floating windows in a real editor ({@link FloatingWindows}): a window a plug-in opens
 * without entering it is drawn over the text where Neovim put it, the keys stay the editor's, and
 * it goes when Neovim closes it; a picker's prompt the cursor is in is drawn with Neovim's cursor,
 * while the editor's caret is hidden. No real plug-in runs here; the float is opened the way one
 * opens its panel ({@code nvim_open_win} with {@code enter = false}), or its prompt (entered, then
 * {@code startinsert}).
 */
class FloatingWindowsEndToEndTest extends EndToEndTestBase {

    /** Opens a float of two rows across the screen's bottom rows, as a panel of key hints is. */
    private static final String OPEN_PANEL = """
            local buf = vim.api.nvim_create_buf(false, true)
            vim.api.nvim_buf_set_lines(buf, 0, -1, false, { 'w  write', 'f  find' })
            local win = vim.api.nvim_get_current_win()
            vim.g.n4e_float = vim.api.nvim_open_win(buf, false, { relative = 'win', win = win,
              row = vim.api.nvim_win_get_height(win) - 2, col = 0,
              width = vim.api.nvim_win_get_width(win), height = 2, style = 'minimal', focusable = false })
            """;

    /** Opens a float of 40 lines showing 5, not entered, that lets the mouse in, as hover text does. */
    private static final String OPEN_LIST = """
            local buf = vim.api.nvim_create_buf(false, true)
            local lines = {}
            for i = 1, 40 do lines[i] = 'entry ' .. i end
            vim.api.nvim_buf_set_lines(buf, 0, -1, false, lines)
            vim.g.n4e_float = vim.api.nvim_open_win(buf, false,
              { relative = 'editor', row = 1, col = 2, width = 20, height = 5, style = 'minimal' })
            """;

    /**
     * Opens a picker's prompt as a picker does: a scratch prompt buffer in a float, entered, then
     * {@code startinsert}. It stays: floats are drawn, so the editor draws it, and the one check
     * for undrawn floats ({@code LuaScripts.WINDOWS}' {@code close_undrawn}) leaves it.
     */
    static final String OPEN_PROMPT = """
            local buf = vim.api.nvim_create_buf(false, true)
            vim.bo[buf].buftype = 'prompt'
            vim.fn.prompt_setprompt(buf, '> ')
            vim.g.n4e_prompt = buf
            vim.g.n4e_float = vim.api.nvim_open_win(buf, true,
              { relative = 'editor', row = 2, col = 4, width = 30, height = 1, border = 'single' })
            vim.cmd.startinsert()
            """;

    @AfterEach
    void closeTheFloat() {
        SessionProbe.lua(session, "vim.cmd.stopinsert()"
                + " pcall(vim.api.nvim_win_close, vim.g.n4e_float or -1, true) vim.g.n4e_float = nil"
                + " pcall(vim.cmd.delmarks, 'q')");
        waitUntil("no float drawn", TIMEOUT_MS, () -> {
            settle();
            return canvas() == null;
        });
    }

    /**
     * ui2's command line as drawn over the editor: the rows of every canvas of ui2's windows,
     * joined. Example: {@code ":"} right after {@code :} was typed.
     */
    private String ui2Drawn() {
        StringBuilder rows = new StringBuilder();
        for (Control child : widget.getChildren()) {
            if (child instanceof GridCanvas canvas && !canvas.isDisposed() && canvas.isVisible()
                    && !StatusLineRow.isRow(canvas) && isNeovimsOwnFloat(canvas)) {
                rows.append(rowsOf(canvas.grid()));
            }
        }
        return rows.toString();
    }

    /** A line run, the focus away in a view and back, then {@code :}: the new line is drawn empty. */
    @Test
    void aNewLineAfterTheFocusCameBackIsDrawnWithoutTheLastOnesText() throws Exception {
        type(widget, ":");
        expectCommandLine(":");
        session.pasteAsync("echo 'stale text'");
        expectCommandLine(":echo 'stale text'");
        type(widget, "\r");
        expectShown("stale text");
        awayAndBackThenColon();
    }

    /**
     * A line left open (not run) when the keyboard goes to a view is abandoned
     * ({@link LineOnFocusLoss}): back in the editor, {@code :} opens an empty line, drawn without
     * the old text. Before, Neovim kept the line, invisible meanwhile, and {@code :} went on its end.
     */
    @Test
    void aLineLeftOpenWhenTheFocusGoesIsAbandoned() throws Exception {
        type(widget, ":");
        expectCommandLine(":");
        session.pasteAsync("echo 'stale text'");
        expectCommandLine(":echo 'stale text'");
        page().showView(IPageLayout.ID_TASK_LIST);
        settle();
        expectCommandLine(""); // abandoned while the view has the keyboard
        page().activate(editor);
        UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
        type(widget, ":");
        expectCommandLine(":");
        waitUntil("the new ':' line drawn without the old text", TIMEOUT_MS, () -> {
            settle();
            String drawn = ui2Drawn();
            return drawn.contains(":") && !drawn.contains("stale text");
        }, () -> "; ui2 is drawn as \"" + ui2Drawn().replace("\n", "\\n") + "\"");
        type(widget, "\u001b");
    }

    /** The keyboard to the Tasks view and back to the editor, then {@code :}: drawn without "stale text". */
    private void awayAndBackThenColon() throws Exception {
        page().showView(IPageLayout.ID_TASK_LIST);
        settle();
        page().activate(editor);
        UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
        type(widget, ":");
        expectCommandLine(":");
        waitUntil("the new ':' line drawn without the old text", TIMEOUT_MS, () -> {
            settle();
            String drawn = ui2Drawn();
            return drawn.contains(":") && !drawn.contains("stale text");
        }, () -> "; ui2 is drawn as \"" + ui2Drawn().replace("\n", "\\n") + "\"");
        type(widget, "\u001b");
    }

    /**
     * The first float's canvas over the editor's text, or {@code null} when none is drawn. ui2's
     * windows, always drawn, and Neovim's popup menu are not this test's floats, and are skipped
     * ({@link #isNeovimsOwnFloat}).
     */
    private GridCanvas canvas() {
        for (Control child : widget.getChildren()) {
            if (child instanceof GridCanvas canvas && !canvas.isDisposed() && !StatusLineRow.isRow(canvas) && !isNeovimsOwnFloat(canvas)) {
                return canvas;
            }
        }
        return null;
    }

    private static String rowsOf(GridSnapshot grid) {
        StringBuilder rows = new StringBuilder();
        for (int r = 0; grid != null && r < grid.height(); r++) {
            rows.append(grid.row(r).stripTrailing()).append('\n');
        }
        return rows.toString();
    }

    private GridCanvas floatShowing(String text) {
        waitUntil("a float showing " + text, TIMEOUT_MS, () -> {
            settle();
            GridCanvas canvas = canvas();
            return canvas != null && rowsOf(canvas.grid()).contains(text);
        }, () -> canvas() == null ? "; no float" : "; it shows " + rowsOf(canvas().grid()));
        return canvas();
    }

    /**
     * A picker's prompt with Neovim's cursor in it: drawn with the cursor, a bar in its insert and
     * a block after Esc, the cursor moving with the letters typed (which are Neovim's, not the
     * document's), the editor's caret hidden meanwhile and back once the prompt is closed.
     */
    @Test
    void aPromptWithTheCursorIsDrawnWithItAndTheEditorsCaretIsHiddenMeanwhile() {
        String text = document.get();
        assertNotNull(widget.getCaret(), "the editor shows its caret");
        SessionProbe.lua(session, OPEN_PROMPT);
        GridCanvas prompt = floatShowing("> ");
        waitUntil("the prompt drawn with Neovim's cursor, a bar, the editor's caret hidden", TIMEOUT_MS, () -> {
            settle();
            return prompt.grid().cursorRow() >= 0 && prompt.isInsertCursor() && widget.getCaret() == null;
        }, () -> "; cursor row " + prompt.grid().cursorRow() + ", bar " + prompt.isInsertCursor() + ", caret "
                + widget.getCaret());
        int start = prompt.grid().cursorCol();
        type(widget, "abc"); // Neovim's insert in the prompt (KeyInput): every key to Neovim
        floatShowing("> abc");
        waitUntil("the cursor after the letters", TIMEOUT_MS, () -> {
            settle();
            return canvas().grid().cursorCol() == start + 3;
        }, () -> "; the cursor is at column " + canvas().grid().cursorCol() + ", it started at " + start);
        type(widget, "\u001b"); // normal mode, still in the prompt
        waitUntil("a block cursor in normal mode, the caret still hidden", TIMEOUT_MS, () -> {
            settle();
            return !canvas().isInsertCursor() && canvas().grid().cursorRow() >= 0 && widget.getCaret() == null;
        });
        assertEquals(text, document.get(), "the letters went to the prompt, not the document");
        SessionProbe.lua(session, "vim.api.nvim_win_close(vim.g.n4e_float, true) vim.g.n4e_float = nil");
        waitUntil("the prompt gone, the editor's caret back", TIMEOUT_MS, () -> {
            settle();
            return canvas() == null && widget.getCaret() != null;
        });
    }

    /** Whether the test's prompt is closed and its insert over. */
    private boolean promptClosed() {
        return Long.valueOf(-1).equals(SessionProbe.lua(session, "return vim.fn.bufwinid(vim.g.n4e_prompt)"))
                && !"i".equals(session.getMode());
    }

    /**
     * Leaving the editor for a view closes the prompt and ends its insert (LineOnFocusLoss): it
     * would be invisible meanwhile, and take the keys on the way back. Back in the editor, its
     * caret is there and an insert is Eclipse's.
     */
    @Test
    void leavingTheEditorForAViewClosesThePromptAndEndsItsInsert() throws Exception {
        String text = document.get();
        SessionProbe.lua(session, OPEN_PROMPT);
        floatShowing("> ");
        type(widget, "ab");
        floatShowing("> ab");
        page().showView(IPageLayout.ID_TASK_LIST);
        waitUntil("the prompt closed, its insert ended", TIMEOUT_MS, () -> {
            settle();
            return promptClosed();
        }, () -> "; mode " + session.getMode());
        page().activate(editor);
        UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
        waitUntil("the prompt not drawn, the caret back", TIMEOUT_MS, () -> {
            settle();
            return canvas() == null && widget.getCaret() != null;
        });
        assertEquals(text, document.get(), "the letters went to the prompt, not the document");
    }

    /**
     * A click in the editor's text, the prompt open in its insert: the prompt closes first, then
     * the click lands in the text, as in Normal mode (abandonLine, NeovimMouse).
     */
    @Test
    void aClickInTheTextClosesThePromptThenLandsInTheText() {
        SessionProbe.lua(session, OPEN_PROMPT);
        floatShowing("> ");
        Rectangle area = widget.getClientArea();
        Event down = new Event();
        down.x = area.x + 2;
        down.y = area.y + 2;
        down.button = 1;
        widget.notifyListeners(SWT.MouseDown, down);
        Event up = new Event();
        up.x = down.x;
        up.y = down.y;
        up.button = 1;
        up.stateMask = SWT.BUTTON1;
        widget.notifyListeners(SWT.MouseUp, up);
        waitUntil("the prompt closed by the click, its insert ended", TIMEOUT_MS, () -> {
            settle();
            return promptClosed() && canvas() == null && widget.getCaret() != null;
        }, () -> "; mode " + session.getMode());
        assertEquals(session.getAttachedWinid(),
                ((Number) SessionProbe.lua(session, "return vim.api.nvim_get_current_win()")).intValue(),
                "Neovim back in the editor's window");
    }

    /** One notch of the wheel away from the user, on {@code canvas}. */
    private static void wheelDown(GridCanvas canvas) {
        Event wheel = new Event();
        wheel.x = canvas.cellWidth() + 1;
        wheel.y = canvas.cellHeight() + 1;
        wheel.count = -3;
        wheel.doit = true;
        canvas.notifyListeners(SWT.MouseVerticalWheel, wheel);
    }

    private long floatTopLine() {
        return ((Number) SessionProbe.lua(session, "return vim.fn.line('w0', vim.g.n4e_float)")).longValue();
    }

    /**
     * The wheel on a float scrolls that window, the cursor staying in the editor's; during an
     * insert Eclipse types the mouse is not Neovim's, there or in the text.
     */
    @Test
    void theWheelScrollsTheFloatUnderItExceptInAnInsertEclipseTypes() {
        Object editorWindow = SessionProbe.lua(session, "return vim.api.nvim_get_current_win()");
        SessionProbe.lua(session, OPEN_LIST);
        GridCanvas canvas = floatShowing("entry 1");
        UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
        wheelDown(canvas);
        waitUntil("the float scrolled", TIMEOUT_MS, () -> floatTopLine() > 1, () -> "; top line " + floatTopLine());
        // 'mouse' empty: the wheel is not sent (NeovimSession.gridMouse, util/MouseOption).
        Object mouseOption = SessionProbe.lua(session, "local m = vim.o.mouse vim.o.mouse = '' return m");
        long before = floatTopLine();
        wheelDown(canvas);
        java.util.concurrent.Future<?> dropped = SessionProbe.queuedWorkDone(session);
        waitUntil("what was queued has run", TIMEOUT_MS, dropped::isDone);
        assertEquals(before, floatTopLine(), "'mouse' empty: the wheel does nothing");
        SessionProbe.lua(session, "vim.o.mouse = '" + mouseOption + "'");
        assertEquals(editorWindow, SessionProbe.lua(session, "return vim.api.nvim_get_current_win()"),
                "the cursor stays in the editor's window");

        long top = floatTopLine();
        type(widget, "i");
        waitUntil("insert mode", TIMEOUT_MS, () -> "i".equals(session.getMode()));
        wheelDown(canvas);
        java.util.concurrent.Future<?> passed = SessionProbe.queuedWorkDone(session);
        waitUntil("what was queued has run", TIMEOUT_MS, passed::isDone);
        // floatTopLine is a call Neovim answers after the input queued before it.
        assertEquals(top, floatTopLine(), "Eclipse types: the wheel is not sent");
        type(widget, "\u001b");
        waitUntil("normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
    }

    /**
     * An insert Eclipse types, carried into a float on the same buffer (a mapping entered it, so
     * no {@code BufEnter}): a click is not sent, since Neovim's text is behind Eclipse's until
     * Esc; a Ctrl letter left to Eclipse still goes to Neovim, through a sync point, and runs no
     * Eclipse command on the document.
     */
    @Test
    void anInsertEclipseTypesCarriedIntoAFloatGetsNoClicksAndKeepsTheCtrlLettersNeovims() {
        UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
        type(widget, "i");
        waitUntil("insert mode", TIMEOUT_MS, () -> "i".equals(session.getMode()));
        SessionProbe.lua(session, """
                vim.g.n4e_ctrl = ''
                vim.keymap.set('i', '<C-a>', function() vim.g.n4e_ctrl = 'a' end, { buffer = 0 })
                vim.g.n4e_float = vim.api.nvim_open_win(0, true,
                  { relative = 'editor', row = 1, col = 2, width = 20, height = 3, style = 'minimal' })
                """);
        waitUntil("the float drawn with Neovim's cursor", TIMEOUT_MS, () -> {
            settle();
            GridCanvas c = canvas();
            return c != null && c.grid().cursorRow() >= 0;
        });
        GridCanvas canvas = canvas();
        for (int type : new int[] { SWT.MouseDown, SWT.MouseUp }) {
            Event e = new Event();
            e.button = 1;
            e.x = 1;
            e.y = 2 * canvas.cellHeight() + 1;
            e.stateMask = type == SWT.MouseUp ? SWT.BUTTON1 : 0;
            canvas.notifyListeners(type, e);
        }
        java.util.concurrent.Future<?> passed = SessionProbe.queuedWorkDone(session);
        waitUntil("what was queued has run", TIMEOUT_MS, passed::isDone);
        assertEquals(1L, SessionProbe.lua(session, "return vim.api.nvim_win_get_cursor(vim.g.n4e_float)[1]"),
                "the click on the third row was not sent");

        UiTestSupport.ctrlKey(widget, 'a');
        waitUntil("Neovim got Ctrl-A", TIMEOUT_MS, () -> "a".equals(SessionProbe.lua(session, "return vim.g.n4e_ctrl")),
                () -> "; got " + SessionProbe.lua(session, "return vim.g.n4e_ctrl"));
        assertEquals(0, widget.getSelectionCount(), "Eclipse's Select All did not run");
        SessionProbe.lua(session, "pcall(vim.keymap.del, 'i', '<C-a>', { buffer = 0 })");
    }

    @Test
    void aPanelOpenedWithoutTheFocusIsDrawnOnTheWindowsBottomEdgeAndTheKeysStayTheEditors() {
        SessionProbe.lua(session, OPEN_PANEL);
        GridCanvas canvas = floatShowing("w  write");
        assertTrue(rowsOf(canvas.grid()).contains("f  find"));
        Rectangle area = widget.getClientArea();
        // On the last text row, as on Neovim's screen: above the status line and the command line,
        // which the editor draws under its text (StatusLineRow).
        int below = widget.getBottomMargin();
        waitUntil("the panel on the window's bottom edge, above the status and command lines", TIMEOUT_MS, () -> {
            settle();
            Rectangle b = canvas.getBounds();
            int bottom = b.y + b.height;
            return bottom <= area.height - below && bottom > area.height - below - widget.getLineHeight();
        }, () -> "; the panel is at " + canvas.getBounds() + " in " + area + ", " + below + " px below the text");
        assertEquals(2 * canvas.cellHeight(), canvas.getBounds().height);
        assertTrue(widget.isFocusControl(), "the editor keeps the keyboard focus");
        type(widget, "mq"); // Neovim's, through the editor: the panel takes no key
        waitUntil("the mark q set by the typed keys", TIMEOUT_MS,
                () -> Long.valueOf(1).equals(SessionProbe.lua(session, "return vim.fn.line(\"'q\") > 0 and 1 or 0")));
        SessionProbe.lua(session, "vim.api.nvim_win_close(vim.g.n4e_float, true)");
        waitUntil("the panel gone when Neovim closed its window", TIMEOUT_MS, () -> {
            settle();
            return canvas() == null;
        });
    }
}
