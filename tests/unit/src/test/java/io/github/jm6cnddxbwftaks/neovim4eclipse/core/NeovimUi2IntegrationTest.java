// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FloatWindow;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * Neovim's ui2 ({@link LuaScripts#UI2}) with the session's real UI attached: what ui2 shows comes
 * with redraws, which the Lua specs' headless Neovim does not make while keys are fed
 * ({@code ui2_spec.lua} pins the rest). The session reports ui2's windows as floats to draw
 * ({@code util/Grids}), with the cursor in its line, whatever the switch for plug-ins' floats says.
 */
@Tag("integration")
class NeovimUi2IntegrationTest {

    private static final String NVIM = TestNeovim.PATH;
    private static final long WAIT_MS = TestWait.millis();

    private static NeovimSession start(String... args) throws Exception {
        TestNeovim.require();
        return NeovimSession.start(NVIM, List.of(args));
    }

    private static void waitFor(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "timed out waiting for " + what);
    }

    private static long number(NeovimSession s, String lua) {
        try {
            return ((Number) s.lua(lua)).longValue();
        } catch (Exception e) {
            return -1;
        }
    }

    /** The last report's float showing window {@code win}, or null. */
    private static FloatWindow window(List<List<FloatWindow>> reports, long win) {
        if (reports.isEmpty()) {
            return null;
        }
        for (FloatWindow f : reports.get(reports.size() - 1)) {
            if (f.grid().window() == win) {
                return f;
            }
        }
        return null;
    }

    private static String text(FloatWindow f) {
        StringBuilder all = new StringBuilder();
        for (int r = 0; r < f.grid().height(); r++) {
            all.append(f.grid().row(r)).append('\n');
        }
        return all.toString();
    }

    /** A typed {@code :} line is ui2's cmd window, drawn with the cursor after the typed text. */
    @Test
    void theTypedLineIsUi2sWindowDrawnWithTheCursor() throws Exception {
        for (String floats : List.of("let g:neovim4eclipse_floating_windows = 1", "let g:neovim4eclipse_floating_windows = 0")) {
            try (NeovimSession s = start("--clean", "--cmd", floats)) {
                List<List<FloatWindow>> reports = new CopyOnWriteArrayList<>();
                s.addFloatingWindowListener(reports::add);
                long cmd = number(s, "return require('vim._core.ui2').wins.cmd");
                assertEquals(1L, number(s, "return vim.o.cmdheight"), floats);
                for (String key : List.of(":", "e", "c", "h")) {
                    s.input(key);
                }
                waitFor(() -> {
                    FloatWindow line = window(reports, cmd);
                    return line != null && line.grid().row(0).startsWith(":ech") && line.grid().cursorCol() == 4;
                }, "ui2's line with ':ech' and the cursor after it (" + floats + "): " + reports);
                FloatWindow line = window(reports, cmd);
                assertEquals(0, line.grid().cursorRow());
                assertEquals(line.hostRows() - 1, line.row(), "over the editor's last row");
                s.input("<Esc>");
            }
        }
    }

    /** A message of three lines and g< show ui2's text in its floats; g< enters the pager, drawn with the cursor. */
    @Test
    void outputAndThePagerAreUi2sFloats() throws Exception {
        try (NeovimSession s = start("--clean")) {
            List<List<FloatWindow>> reports = new CopyOnWriteArrayList<>();
            s.addFloatingWindowListener(reports::add);
            for (String key : List.of(":", "e", "c", "h", "o", " ", "\"", "o", "n", "e", "\\", "n", "t", "w", "o", "\"", "<CR>")) {
                s.input(key);
            }
            long pager = number(s, "return require('vim._core.ui2').wins.pager");
            s.input("g");
            s.input("<lt>");
            waitFor(() -> number(s, "return vim.api.nvim_get_current_win()") == pager, "the cursor in the pager");
            waitFor(() -> {
                FloatWindow f = window(reports, pager);
                return f != null && text(f).contains("two") && f.grid().cursorRow() >= 0;
            }, "the pager drawn with the output and the cursor: " + reports);
            s.input("q");
            waitFor(() -> number(s, "return vim.api.nvim_get_current_win()") != pager, "q leaves the pager");
        }
    }

    /**
     * A line Eclipse carries out is run as its {@code :Eclipse} replacement; the command row then
     * shows the line as typed, as Neovim leaves a line that ran, not the replacement's words.
     */
    @Test
    void theRowShowsTheLineAsTypedNotItsEclipseReplacement() throws Exception {
        try (NeovimSession s = start("--clean")) {
            for (String key : List.of(":", "w", "i", "n", "c", "m", "d", " ", "l", "<CR>")) {
                s.input(key);
            }
            String lua = "return vim.api.nvim_buf_get_lines(require('vim._core.ui2').bufs.cmd, 0, -1, false)[1] or ''";
            waitFor(() -> {
                try {
                    return ":wincmd l".equals(s.lua(lua));
                } catch (Exception e) {
                    return false;
                }
            }, "':wincmd l' on the command row: " + Ui2Text.shown(s));
            assertEquals("wincmd l", s.lua("return vim.fn.histget(':', -1)"), "the history holds it as typed too");
        }
    }

    /**
     * A user's {@code 'cmdheight'} 0 ({@code init.lua}, here {@code --cmd}) is kept as a wish: Neovim
     * stays at 1, the session reports no row for the line, and messages go to ui2's message window;
     * {@code :set ch=1} and {@code :set ch=0} later switch both ways.
     */
    @Test
    void cmdheightZeroKeepsNeovimsRowAndTellsTheEditor() throws Exception {
        try (NeovimSession s = start("--clean", "--cmd", "set cmdheight=0")) {
            String target = "return require('vim._core.ui2').cfg.msg.target";
            waitFor(() -> s.commandLineRows() == 0, "no row for the line");
            assertEquals(1L, number(s, "return vim.o.cmdheight"), "Neovim's own stays 1: no window shrinks");
            assertEquals("msg", s.lua(target), "messages to ui2's message window");
            s.lua("vim.o.cmdheight = 1");
            waitFor(() -> s.commandLineRows() == 1, "the row back after :set ch=1");
            assertEquals("cmd", s.lua(target), "messages back on the line's row");
            s.lua("vim.o.cmdheight = 0");
            waitFor(() -> s.commandLineRows() == 0 && number(s, "return vim.o.cmdheight") == 1,
                    "no row again after :set ch=0, and Neovim's back at 1");
            assertEquals("msg", s.lua(target));
        }
    }

    /**
     * Eclipse's UI attaches with {@code ext_messages}, and Neovim then sets {@code 'cmdheight'} 0
     * itself: that is not the user's wish, so the row stays. A 0 set from Lua in the config is.
     */
    @Test
    void onlyAZeroTheUserSetIsTheirWish() throws Exception {
        try (NeovimSession s = start("--clean")) {
            assertEquals(1, s.commandLineRows(), "no config: the row stays");
            assertEquals("cmd", s.lua("return require('vim._core.ui2').cfg.msg.target"));
        }
        try (NeovimSession s = start("--clean", "--cmd", "lua vim.o.cmdheight = 0")) {
            waitFor(() -> s.commandLineRows() == 0, "a 0 from the config's Lua");
        }
    }

    /** A config that chose ui2's message window itself keeps it when 'cmdheight' goes from 0 to 1. */
    @Test
    void aConfigsOwnMessageTargetIsKept() throws Exception {
        try (NeovimSession s = start("--clean", "--cmd", "set cmdheight=0",
                "--cmd", "lua require('vim._core.ui2').cfg.msg.target = 'msg'")) {
            waitFor(() -> s.commandLineRows() == 0, "no row for the line");
            s.lua("vim.o.cmdheight = 1");
            waitFor(() -> s.commandLineRows() == 1, "the row back");
            assertEquals("msg", s.lua("return require('vim._core.ui2').cfg.msg.target"), "the config's choice, not moved back");
        }
    }

    /**
     * With {@code 'cmdheight'} 0, a prompt's list ({@code inputlist()}, {@code z=}) stays with its
     * prompt, in ui2's dialog window, not in the message window, which would drop it after a few
     * seconds while the prompt still waits.
     */
    @Test
    void aPromptsListStaysWithItsPromptWithCmdheightZero() throws Exception {
        try (NeovimSession s = start("--clean", "--cmd", "set cmdheight=0")) {
            waitFor(() -> s.commandLineRows() == 0, "no row for the line");
            String dialog = "local b = require('vim._core.ui2').bufs.dialog;"
                    + " return vim.api.nvim_buf_is_valid(b) and table.concat(vim.api.nvim_buf_get_lines(b, 0, -1, false), '|') or ''";
            String msg = "return table.concat(vim.api.nvim_buf_get_lines(require('vim._core.ui2').bufs.msg, 0, -1, false), '|')";
            s.input(":call inputlist(['Pick one:', '1. alpha', '2. beta'])<CR>");
            waitFor(() -> {
                try {
                    return String.valueOf(s.lua(dialog)).contains("2. beta");
                } catch (Exception e) {
                    return false;
                }
            }, "the list in the prompt's dialog window: " + Ui2Text.shown(s));
            assertTrue(!String.valueOf(s.lua(msg)).contains("2. beta"), "not in the message window");
            s.input("1<CR>");
        }
    }

    /**
     * A 0 set where no OptionSet fires ({@code :noautocmd}, an autocommand without
     * {@code nested}) is taken as the user's 0 too, at Neovim's next wait for input: the row goes,
     * and Neovim's own is back at 1, so its window keeps the editor's rows.
     */
    @Test
    void aZeroSetWithoutAutocommandsIsTakenToo() throws Exception {
        try (NeovimSession s = start("--clean")) {
            assertEquals(1, s.commandLineRows());
            s.lua("vim.cmd('noautocmd set cmdheight=0')");
            waitFor(() -> s.commandLineRows() == 0 && number(s, "return vim.o.cmdheight") == 1,
                    "the overlay on and Neovim back at 1");
            assertEquals("msg", s.lua("return require('vim._core.ui2').cfg.msg.target"));
        }
    }

    /**
     * With {@code 'cmdheight'} 0, output of more than one line is shown on ui2's expanded line,
     * which the editor draws, until the next key; one line and errors go to the message window.
     */
    @Test
    void withCmdheightZeroLongOutputStaysUntilAKey() throws Exception {
        try (NeovimSession s = start("--clean", "--cmd", "set cmdheight=0")) {
            waitFor(() -> s.commandLineRows() == 0, "no row for the line");
            List<List<FloatWindow>> reports = new CopyOnWriteArrayList<>();
            s.addFloatingWindowListener(reports::add);
            long cmd = number(s, "return require('vim._core.ui2').wins.cmd");
            s.input(":echo \"one\\ntwo\\nthree\"<CR>");
            waitFor(() -> {
                FloatWindow line = window(reports, cmd);
                return line != null && text(line).contains("three") && line.grid().height() >= 3;
            }, "the output on the expanded line, drawn: " + reports);
            String msg = "return table.concat(vim.api.nvim_buf_get_lines(require('vim._core.ui2').bufs.msg, 0, -1, false), '|')";
            assertTrue(!String.valueOf(s.lua(msg)).contains("three"), "not in the message window");
            s.input("j");
            waitFor(() -> window(reports, cmd) == null, "gone at the next key");
            s.input(":echo \"hi\"<CR>");
            waitFor(() -> {
                try {
                    return String.valueOf(s.lua(msg)).contains("hi");
                } catch (Exception e) {
                    return false;
                }
            }, "one line: the message window");
        }
    }
}
