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
 * A real snacks.nvim picker, at the commit pinned and vetted in
 * {@code releng/test-plugins.properties}, through the session: its {@code lines} picker (the
 * buffer's lines, pure Lua, so on every system). A picker of its own drawing, not Telescope's:
 * an input window with the keys in Insert mode, a list, a preview, a box around them and a
 * backdrop, each a float. What only the real plug-in shows: the letters reach its input and
 * narrow the list, Enter jumps to the line chosen, Eclipse mode closes the picker, and the
 * document never changes. The same drawn in a real editor is {@code SnacksPickerEndToEndTest}.
 * Skipped without the plug-in ({@link PinnedPlugins#HOW}).
 */
@Tag("integration")
class SnacksPickerIntegrationTest {

    private static final List<String> LINES = List.of("alpha", "beta", "gamma", "delta");

    /** A session with snacks loaded and {@code gs} mapped to its {@code lines}, on {@link #LINES}. */
    private static NeovimSession start() throws Exception {
        TestNeovim.require();
        Optional<Path> snacks = PinnedPlugins.find("snacks");
        assumeTrue(snacks.isPresent(), "snacks is not fetched at its pinned commit: " + PinnedPlugins.HOW);
        NeovimSession s = NeovimSession.start(TestNeovim.PATH, List.of("--clean"));
        try {
            s.lua(PinnedPlugins.loadSnacks(snacks.get()) + " return 0");
            s.setBufferLines(LINES);
            return s;
        } catch (Throwable t) { // a skip too: the session is not the caller's yet
            s.close();
            throw t;
        }
    }

    /** Opens the picker with {@code gs} and waits for its list to show every line, the keys in its input. */
    private static void openPicker(NeovimSession s) throws Exception {
        s.inputAsync("gs");
        waitFor(() -> "i".equals(s.getMode()) && s.inPluginFloat() && snacksWindows(s, "delta") >= 1
                && "snacks_picker_input".equals(s.lua("return vim.bo.filetype")),
                "snacks' picker drawn, every line in its list, the keys in its input");
    }

    @Test
    void theLettersNarrowTheListAndEnterJumpsToTheLineChosen() throws Exception {
        try (NeovimSession s = start()) {
            openPicker(s);
            s.inputAsync("gam");
            waitFor(() -> snacksWindows(s, "gamma") >= 1 && listHas(s, "beta") == 0, "the list narrowed to gamma");
            s.inputAsync("<CR>");
            waitFor(() -> snacksWindows(s, null) == 0 && !s.inPluginFloat() && "n".equals(s.getMode()),
                    "the picker closed, Eclipse's mode back to Normal, as Neovim's is");
            assertEquals(3, ((Number) s.lua("return vim.api.nvim_win_get_cursor(0)[1]")).intValue(), "on gamma's line");
            assertEquals(LINES, s.getBufferLines(), "the letters went to the picker, not the document");
        }
    }

    /** Eclipse mode closes the picker ({@link NeovimSession#leaveFloatAsync}), since its keys are gone. */
    @Test
    void eclipseModeClosesThePicker() throws Exception {
        try (NeovimSession s = start()) {
            openPicker(s);
            s.inputAsync("al");
            waitFor(() -> listHas(s, "beta") == 0, "the letters in the input");
            s.leaveFloatAsync();
            waitFor(() -> snacksWindows(s, null) == 0 && !s.inPluginFloat() && "n".equals(s.getMode()),
                    "the picker closed, the mode Normal");
            assertEquals(LINES, s.getBufferLines(), "the document unchanged");
        }
    }

    /** {@link PinnedPlugins#SNACKS_WINDOWS}' answer, with {@code word} (a plain word of the test's own) or none. */
    private static int snacksWindows(NeovimSession s, String word) throws Exception {
        Object n = s.lua("return (function(...)\n" + PinnedPlugins.SNACKS_WINDOWS + "\nend)("
                + (word == null ? "" : "'" + word + "'") + ")");
        return ((Number) n).intValue();
    }

    /** {@link PinnedPlugins#SNACKS_LIST_HAS}' answer for {@code word}, a plain word of the test's own. */
    private static int listHas(NeovimSession s, String word) throws Exception {
        Object n = s.lua("return (function(...)\n" + PinnedPlugins.SNACKS_LIST_HAS + "\nend)('" + word + "')");
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
