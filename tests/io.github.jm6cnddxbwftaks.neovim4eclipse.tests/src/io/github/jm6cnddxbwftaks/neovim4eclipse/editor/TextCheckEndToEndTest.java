// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WelcomeNote;

/**
 * The text check that ends every UI test ({@link TextCheck}, run by {@code EndToEndTestBase}): that
 * it finds a difference and names it, and that a test left in insert mode is compared after its
 * {@code Esc}, when Neovim has the typed text; and that {@link TextMayDiffer} leaves a test out.
 */
class TextCheckEndToEndTest extends EndToEndTestBase {

    /** A test left Eclipse mode on: the next test starts in Neovim mode again. */
    private boolean eclipseModeLeftOn;

    @AfterEach
    void neovimModeBackOn() throws Exception {
        if (eclipseModeLeftOn) {
            runCommand(WelcomeNote.TOGGLE_COMMAND_ID);
            settle();
        }
    }

    /**
     * Ends with the two copies different, which the check would fail: it passes only because the
     * marker leaves it out. Neovim mode comes back on in the clean-up, after the check.
     */
    @Test
    @TextMayDiffer("ends in Eclipse mode, with an edit Neovim has not been sent")
    void aTestMarkedTextMayDifferIsNotCompared() throws Exception {
        runCommand(WelcomeNote.TOGGLE_COMMAND_ID);
        eclipseModeLeftOn = true;
        document.replace(document.getLineOffset(1), 4, "BETA");
        assertNotNull(TextCheck.differences(TIMEOUT_MS), "the copies differ when the test ends");
    }

    /**
     * Eclipse mode: Eclipse's edits are not pushed until Neovim mode is back on, so the two copies
     * differ meanwhile, as after a drift. Neovim mode on again pushes the document: they agree.
     */
    @Test
    void aDifferenceIsFoundAndNamed() throws Exception {
        runCommand(WelcomeNote.TOGGLE_COMMAND_ID);
        document.replace(document.getLineOffset(1), 4, "BETA");
        String differences = TextCheck.differences(TIMEOUT_MS);
        assertNotNull(differences, "Eclipse has BETA, Neovim beta");
        assertTrue(differences.contains("line 2 is \"BETA\" in Eclipse, \"beta\" in Neovim"), differences);

        runCommand(WelcomeNote.TOGGLE_COMMAND_ID);
        settle();
        assertNull(TextCheck.differences(TIMEOUT_MS), "Neovim mode on again pushes the document");
    }

    /**
     * Text Eclipse typed in insert mode reaches Neovim at Esc, which the check presses first.
     *
     * <p>The check finds the test file's buffer by its number, while {@code nvim_get_current_line()}
     * reads the current window's buffer, which can be another one (a buffer left over from an
     * earlier test, say). So the line is read together with where it came from ({@link #CURRENT}),
     * in one call, and a failure message says which window and buffer were current at that read and
     * what the test file's buffer held.
     */
    @Test
    void aTestLeftInInsertModeIsComparedAfterItsEsc() {
        type(widget, "iX");
        waitUntilInsert();
        assertNull(TextCheck.differences(TIMEOUT_MS));
        assertEquals("n", session.getMode());
        int bufnr = EditorAttachManager.getInstance().bufferNumberOf(document);
        List<?> read = (List<?>) SessionProbe.lua(session, CURRENT.formatted(bufnr));
        assertEquals("Xalpha", read.get(0), () -> String.valueOf(read.get(1)));
    }

    /**
     * Lua that returns {@code { current line, where it came from }}: the current window and buffer
     * (number, name, first line), the test file's buffer ({@code %d}, the number the plug-in keeps
     * for its document, 0 for none): whether it is loaded, its windows and lines, and every buffer's
     * number and name. Example of the second: {@code "window 1000, buffer 3 (/tmp/ws/p/test.txt,
     * first line "Xalpha"); test.txt is buffer 3, loaded, in windows { 1000 }: { "Xalpha",
     * "beta", "gamma" }; buffers 2: 3:/tmp/ws/p/test.txt"}.
     */
    private static final String CURRENT = """
            local win = vim.api.nvim_get_current_win()
            local current = vim.api.nvim_get_current_buf()
            local line = vim.api.nvim_get_current_line()
            local test = %d
            local valid = test > 0 and vim.api.nvim_buf_is_valid(test)
            local all = {}
            for _, b in ipairs(vim.api.nvim_list_bufs()) do
              all[#all + 1] = b .. ':' .. vim.api.nvim_buf_get_name(b)
            end
            local first = vim.api.nvim_buf_get_lines(current, 0, 1, false)[1] or ''
            local where = 'window ' .. win .. ', buffer ' .. current
                .. ' (' .. vim.api.nvim_buf_get_name(current) .. ', first line ' .. vim.inspect(first) .. '); '
                .. 'test.txt is buffer ' .. test
            if valid then
              where = where .. ', ' .. (vim.api.nvim_buf_is_loaded(test) and 'loaded' or 'not loaded')
                  .. ', in windows ' .. vim.inspect(vim.fn.win_findbuf(test))
                  .. ': ' .. vim.inspect(vim.api.nvim_buf_get_lines(test, 0, -1, false))
            else
              where = where .. ': none'
            end
            return { line, where .. '; buffers ' .. table.concat(all, ' ') }
            """;

    private void waitUntilInsert() {
        UiTestSupport.waitUntil("insert mode", TIMEOUT_MS, () -> "i".equals(session.getMode()));
        expectText("Xalpha\nbeta\ngamma\n");
    }
}
