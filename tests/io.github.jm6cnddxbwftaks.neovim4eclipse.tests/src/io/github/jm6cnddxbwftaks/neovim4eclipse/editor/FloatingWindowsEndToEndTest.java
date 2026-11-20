// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IPageLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;

/**
 * Neovim's floating windows without the focus in a real editor ({@link FloatingWindows}): a window
 * a plug-in opens without entering it is drawn over the text where Neovim put it, the keys stay
 * the editor's, and it goes when Neovim closes it. No real plug-in runs here; the float is opened
 * the way one opens its panel ({@code nvim_open_win} with {@code enter = false}).
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

    @AfterEach
    void closeTheFloat() {
        SessionProbe.lua(session, "pcall(vim.api.nvim_win_close, vim.g.n4e_float or -1, true) vim.g.n4e_float = nil"
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

    @Test
    void aPanelOpenedWithoutTheFocusIsDrawnOnTheEditorsBottomEdgeAndTheKeysStayTheEditors() {
        SessionProbe.lua(session, OPEN_PANEL);
        GridCanvas canvas = floatShowing("w  write");
        assertTrue(rowsOf(canvas.grid()).contains("f  find"));
        Rectangle area = widget.getClientArea();
        waitUntil("the panel on the editor's bottom edge", TIMEOUT_MS, () -> {
            settle();
            Rectangle b = canvas.getBounds();
            return b.y + b.height == area.y + area.height;
        }, () -> "; the panel is at " + canvas.getBounds() + " in " + area);
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
