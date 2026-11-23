// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IPageLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.PinnedPlugins;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;

/**
 * A real picker in a real editor: Telescope's {@code current_buffer_fuzzy_find} (pure Lua, no
 * {@code rg} or {@code fd}), at the commit pinned and vetted in {@code releng/test-plugins.properties},
 * opened from a mapping as a user's {@code <leader>/} would. Its windows are drawn over the text,
 * its prompt with Neovim's cursor and the editor's caret hidden; the letters typed go to the
 * prompt; Enter jumps to the line found; leaving the editor for a view closes every window of it.
 * The keys' routing alone, without a workbench, is {@code FloatInsertIntegrationTest}; the
 * drawing with a stand-in prompt, {@link FloatingWindowsEndToEndTest}. Skipped when the
 * plug-ins are not fetched ({@link PinnedPlugins#HOW}).
 *
 * <p>The UI tests share one Neovim, so Telescope and plenary stay on its {@code 'runtimepath'},
 * their modules loaded, for the tests after this one; nothing of theirs acts by itself (their
 * {@code plugin/} files, read only at Neovim's start, are never read here), and the mapping goes.
 */
class PickersEndToEndTest extends EndToEndTestBase {

    @BeforeEach
    void loadTelescope() {
        Optional<Path> telescope = PinnedPlugins.find("telescope");
        Optional<Path> plenary = PinnedPlugins.find("plenary");
        assumeTrue(telescope.isPresent() && plenary.isPresent(),
                "telescope and plenary are not fetched at their pinned commits: " + PinnedPlugins.HOW);
        SessionProbe.lua(session, PinnedPlugins.loadTelescope(plenary.get(), telescope.get()));
    }

    @AfterEach
    void closeTelescope() {
        SessionProbe.lua(session, PinnedPlugins.CLOSE_TELESCOPE + " pcall(vim.keymap.del, 'n', 'gp')");
        waitUntil("no picker drawn", TIMEOUT_MS, () -> {
            settle();
            return pluginFloats().isEmpty();
        });
    }

    /** The canvases of plug-ins' floats over the text (not ui2's windows, the popup menu or a status line). */
    private List<GridCanvas> pluginFloats() {
        List<GridCanvas> floats = new ArrayList<>();
        for (Control child : widget.getChildren()) {
            if (child instanceof GridCanvas canvas && !canvas.isDisposed() && !StatusLineRow.isRow(canvas)
                    && !isNeovimsOwnFloat(canvas)) {
                floats.add(canvas);
            }
        }
        return floats;
    }

    private static String rowsOf(GridSnapshot grid) {
        StringBuilder rows = new StringBuilder();
        for (int r = 0; grid != null && r < grid.height(); r++) {
            rows.append(grid.row(r).stripTrailing()).append('\n');
        }
        return rows.toString();
    }

    /** {@link PinnedPlugins#TELESCOPE_WINDOWS}' answer, with {@code word} (a plain word of the test's own) or none. */
    private long telescopeWindows(String word) {
        Object n = SessionProbe.lua(session, "return (function(...)\n" + PinnedPlugins.TELESCOPE_WINDOWS + "\nend)("
                + (word == null ? "" : "'" + word + "'") + ")");
        return ((Number) n).longValue();
    }

    /** The drawn floats' rows, one float after the other: for a failure's message. */
    private String drawn() {
        StringBuilder all = new StringBuilder();
        for (GridCanvas canvas : pluginFloats()) {
            all.append(rowsOf(canvas.grid())).append("---\n");
        }
        return all.toString();
    }

    /** Whether a drawn float shows {@code text}, and the one with Neovim's cursor is its prompt. */
    private boolean pickerShows(String text) {
        settle();
        boolean shown = pluginFloats().stream().anyMatch(c -> rowsOf(c.grid()).contains(text));
        boolean prompt = pluginFloats().stream().anyMatch(c -> c.grid().cursorRow() >= 0 && rowsOf(c.grid()).contains(">"));
        return shown && prompt && widget.getCaret() == null;
    }

    /**
     * The picker drawn, the prompt with the cursor and the caret hidden, the letters typed in the
     * prompt and not the document, Enter jumping to the line found, the caret back on it.
     */
    @Test
    void telescopesPromptTakesTheLettersAndEnterJumpsToTheLineFound() {
        String text = document.get();
        type(widget, "gp");
        waitUntil("Telescope drawn, its prompt with Neovim's cursor, the editor's caret hidden", TIMEOUT_MS,
                () -> pickerShows("beta"), () -> "; drawn:\n" + drawn() + "caret " + widget.getCaret());
        type(widget, "gam");
        // Narrowed, as Neovim has it: the prompt with the letters, the results only gamma (a
        // preview, shown on a wide editor, still has every line, so the drawing alone cannot tell).
        waitUntil("the prompt with the letters, the results narrowed to gamma", TIMEOUT_MS,
                () -> pickerShows("gam") && telescopeWindows("gam") >= 2 && telescopeWindows("alpha") == 0
                        && telescopeWindows("beta") == 0,
                () -> "; drawn:\n" + drawn());
        assertEquals(text, document.get(), "the letters went to the prompt, not the document");
        type(widget, "\r");
        waitUntil("the picker gone, the caret back on gamma's line", TIMEOUT_MS, () -> {
            settle();
            return pluginFloats().isEmpty() && widget.getCaret() != null
                    && widget.getLineAtOffset(widget.getCaretOffset()) == 2;
        }, () -> "; drawn:\n" + drawn() + "caret on line " + widget.getLineAtOffset(widget.getCaretOffset()));
        assertEquals(text, document.get(), "the document keeps its text");
    }

    /** Telescope's results window as drawn, or {@code null}. */
    private GridCanvas resultsCanvas() {
        long win = ((Number) SessionProbe.lua(session, """
                for _, w in ipairs(vim.api.nvim_list_wins()) do
                  if vim.bo[vim.api.nvim_win_get_buf(w)].filetype == 'TelescopeResults' then return w end
                end
                return -1""")).longValue();
        return pluginFloats().stream().filter(c -> c.grid() != null && c.grid().window() == win).findFirst().orElse(null);
    }

    /** The line number of the entry selected in the open picker, 0 when none. */
    private long selectedLine() {
        Object line = SessionProbe.lua(session, """
                local ok, entry = pcall(function() return require('telescope.actions.state').get_selected_entry() end)
                return ok and entry and entry.lnum or 0""");
        return ((Number) line).longValue();
    }

    /** A left click (press and release) on cell ({@code row}, {@code col}) of {@code canvas}. */
    private static void click(GridCanvas canvas, int row, int col) {
        for (int type : new int[] { SWT.MouseDown, SWT.MouseUp }) {
            Event e = new Event();
            e.button = 1;
            e.x = col * canvas.cellWidth() + 1;
            e.y = row * canvas.cellHeight() + 1;
            e.stateMask = type == SWT.MouseUp ? SWT.BUTTON1 : 0;
            canvas.notifyListeners(type, e);
        }
    }

    /**
     * A click on a row of the results selects it, Telescope's own mapping, and the picker stays
     * with the keyboard in its prompt; a second click on the row, a double click to Neovim, opens
     * it. {@code 'mousetime'} is raised so that a slow machine still counts the two as one.
     */
    @Test
    void aClickOnTheResultsSelectsTheRowAndADoubleClickOpensIt() {
        Object mousetime = SessionProbe.lua(session, "local t = vim.o.mousetime vim.o.mousetime = 60000 return t");
        try {
            String text = document.get();
            type(widget, "gp");
            waitUntil("Telescope drawn", TIMEOUT_MS, () -> pickerShows("gamma") && resultsCanvas() != null,
                    () -> "; drawn:\n" + drawn());
            UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
            GridCanvas results = resultsCanvas();
            assertNotNull(results);
            int row = -1;
            for (int r = 0; r < results.grid().height() && row < 0; r++) {
                row = results.grid().row(r).contains("gamma") ? r : -1;
            }
            assertTrue(row >= 0, "gamma's row in the results:\n" + rowsOf(results.grid()));
            int col = results.grid().row(row).indexOf("gamma");
            assertFalse(selectedLine() == 3, "another entry is selected first");

            click(results, row, col);
            waitUntil("gamma selected by the click", TIMEOUT_MS, () -> selectedLine() == 3,
                    () -> "; selected line " + selectedLine());
            assertTrue(pickerShows("gamma"), "the picker stays, its prompt with the cursor");
            assertEquals("i", session.getMode(), "the prompt keeps its insert");

            click(results, row, col);
            waitUntil("the picker gone, the caret on gamma's line", TIMEOUT_MS, () -> {
                settle();
                return pluginFloats().isEmpty() && widget.getCaret() != null
                        && widget.getLineAtOffset(widget.getCaretOffset()) == 2;
            }, () -> "; drawn:\n" + drawn());
            assertEquals(text, document.get(), "the document keeps its text");
        } finally {
            SessionProbe.lua(session, "vim.o.mousetime = " + mousetime);
        }
    }

    /** Leaving the editor for a view closes every window of the picker and ends its insert. */
    @Test
    void leavingTheEditorClosesThePicker() throws Exception {
        String text = document.get();
        type(widget, "gp");
        waitUntil("Telescope drawn", TIMEOUT_MS, () -> pickerShows("beta"), () -> "; drawn:\n" + drawn());
        type(widget, "al");
        waitUntil("the letters in the prompt", TIMEOUT_MS, () -> pickerShows("al"), () -> "; drawn:\n" + drawn());
        page().showView(IPageLayout.ID_TASK_LIST);
        waitUntil("every window of the picker closed, its insert ended", TIMEOUT_MS, () -> {
            settle();
            return telescopeWindows(null) == 0 && !"i".equals(session.getMode());
        }, () -> "; mode " + session.getMode());
        page().activate(editor);
        UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
        waitUntil("nothing of the picker drawn, the caret back", TIMEOUT_MS, () -> {
            settle();
            return pluginFloats().isEmpty() && widget.getCaret() != null;
        }, () -> "; drawn:\n" + drawn());
        assertEquals(text, document.get(), "the letters went to the prompt, not the document");
    }
}
