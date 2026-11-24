// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.PinnedPlugins;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * A real fzf-lua, at the commit pinned and vetted in {@code releng/test-plugins.properties}, with
 * the fzf program on {@code PATH}, through the session. Its list is a terminal running fzf in a
 * float: the keys go to fzf in Terminal mode ({@code t}), and the float closes with
 * {@code 'eventignore'} set, without a {@code ModeChanged}, so only the plug-in's own read at
 * the closing brings Eclipse's mode back to Normal. What only the real plug-in shows: the letters
 * reach fzf and narrow its list, Enter jumps to the line chosen, Eclipse mode closes the list,
 * and the document never changes. fzf itself may go on running in a hidden buffer after
 * the list closes (fzf-lua's default "hide" profile, for {@code :FzfLua resume}, as in a
 * terminal), so that is not checked. The same drawn in a real editor is
 * {@code FzfLuaEndToEndTest}; a terminal float alone, without fzf-lua,
 * {@code NeovimWindowsIntegrationTest}. Skipped without the plug-in ({@link PinnedPlugins#HOW}) or
 * without fzf ({@link PinnedPlugins#FZF_HOW}), or with one too old for it ({@link PinnedPlugins#FZF_TOO_OLD});
 * an fzf whose version fzf-lua cannot read fails them.
 */
@Tag("integration")
class FzfLuaIntegrationTest {

    private static final List<String> LINES = List.of("alpha", "beta", "gamma", "delta");

    /** A session with fzf-lua loaded and {@code gz} mapped to its {@code blines}, on {@link #LINES}. */
    private static NeovimSession start() throws Exception {
        TestNeovim.require();
        Optional<Path> fzfLua = PinnedPlugins.find("fzf-lua");
        assumeTrue(fzfLua.isPresent(), "fzf-lua is not fetched at its pinned commit: " + PinnedPlugins.HOW);
        Optional<Path> fzf = PinnedPlugins.fzf();
        assumeTrue(fzf.isPresent(), PinnedPlugins.FZF_HOW);
        NeovimSession s = NeovimSession.start(TestNeovim.PATH, List.of("--clean"));
        try {
            s.lua(PinnedPlugins.loadFzfLua(fzfLua.get(), fzf.get()) + " return 0");
            String check = String.valueOf(s.lua(PinnedPlugins.FZF_CHECK));
            assumeTrue(!PinnedPlugins.FZF_IS_TOO_OLD.equals(check), PinnedPlugins.FZF_TOO_OLD);
            assertEquals(PinnedPlugins.FZF_IS_ACCEPTED, check, "fzf-lua takes the fzf on PATH");
            s.setBufferLines(LINES);
            return s;
        } catch (Throwable t) { // a skip too: the session is not the caller's yet
            s.close();
            throw t;
        }
    }

    /** Opens the list with {@code gz} and waits for fzf to show every line, in Terminal mode in its float. */
    private static void openList(NeovimSession s) throws Exception {
        s.inputAsync("gz");
        waitFor(() -> "t".equals(s.getMode()) && s.inPluginFloat() && fzfWindows(s, "delta") == 1,
                "fzf-lua's list drawn, every line in it, the keys going to fzf");
    }

    @Test
    void theLettersNarrowFzfsListAndEnterJumpsToTheLineChosen() throws Exception {
        try (NeovimSession s = start()) {
            openList(s);
            s.inputAsync("gam");
            waitFor(() -> fzfWindows(s, "gamma") == 1 && fzfWindows(s, "beta") == 0, "fzf's list narrowed to gamma");
            s.inputAsync("<CR>");
            waitFor(() -> fzfWindows(s, null) == 0 && !s.inPluginFloat() && "n".equals(s.getMode()),
                    "the list closed, Eclipse's mode back to Normal, as Neovim's is");
            assertEquals(3, ((Number) s.lua("return vim.api.nvim_win_get_cursor(0)[1]")).intValue(), "on gamma's line");
            assertEquals(LINES, s.getBufferLines(), "the letters went to fzf, not the document");
        }
    }

    /**
     * Eclipse mode closes the list ({@link NeovimSession#leaveFloatAsync}), since its keys are
     * gone. Leaving the editor for a view only moves Neovim out of it ({@code FzfLuaEndToEndTest}).
     */
    @Test
    void eclipseModeClosesTheList() throws Exception {
        try (NeovimSession s = start()) {
            openList(s);
            s.leaveFloatAsync();
            waitFor(() -> fzfWindows(s, null) == 0 && !s.inPluginFloat() && "n".equals(s.getMode()),
                    "the list closed, the mode Normal");
            assertEquals(LINES, s.getBufferLines(), "the document unchanged");
        }
    }

    /** {@link PinnedPlugins#FZF_LUA_WINDOWS}' answer, with {@code word} (a plain word of the test's own) or none. */
    private static int fzfWindows(NeovimSession s, String word) throws Exception {
        Object n = s.lua("return (function(...)\n" + PinnedPlugins.FZF_LUA_WINDOWS + "\nend)("
                + (word == null ? "" : "'" + word + "'") + ")");
        return ((Number) n).intValue();
    }

    private interface Check {
        boolean holds() throws Exception;
    }

    /** Waits for Neovim's or the session's state, read with RPC calls that may fail while it changes. */
    private static void waitFor(Check condition, String what) throws Exception {
        BooleanSupplier quiet = () -> {
            try {
                return condition.holds();
            } catch (Exception e) {
                return false;
            }
        };
        long deadline = System.currentTimeMillis() + TestWait.millis();
        while (!quiet.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }
}
