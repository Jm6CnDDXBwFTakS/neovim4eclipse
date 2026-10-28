// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * Neovim's window made the editor's size through the session (docs/design/VIEWPORT.md, 3.1):
 * {@link NeovimSession#setEditorSizeAsync} with a real Neovim, which only then has a UI to resize
 * ({@code nvim_ui_try_resize}). The overhead and wrap scripts on their own are
 * {@code viewport_spec.lua}; the grid arithmetic {@code ViewportSizeTest}. Skipped when no
 * {@code nvim} is found.
 */
@Tag("integration")
class NeovimViewportIntegrationTest {

    private static NeovimSession start(String... extraArgs) throws Exception {
        assumeTrue(NvimExecutable.looksAvailable(TestNeovim.PATH), "nvim not available");
        List<String> args = new ArrayList<>(List.of("--clean"));
        args.addAll(List.of(extraArgs));
        return NeovimSession.start(TestNeovim.PATH, args);
    }

    /** The window's text area, {@code [width, height]}: winwidth minus the number column, winheight. */
    private static List<?> window(NeovimSession s) throws Exception {
        return (List<?>) s.lua("local w = vim.api.nvim_get_current_win() "
                + "return { vim.fn.winwidth(w) - vim.fn.getwininfo(w)[1].textoff, vim.fn.winheight(w) }");
    }

    private static boolean windowIs(NeovimSession s, int width, int height) {
        try {
            List<?> size = window(s);
            return ((Number) size.get(0)).intValue() == width && ((Number) size.get(1)).intValue() == height;
        } catch (Exception e) {
            return false;
        }
    }

    private static void waitFor(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + TestWait.millis();
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "timed out waiting for " + what);
    }

    @Test
    void theWindowGetsTheEditorsSizeAndLGoesToItsLastLine() throws Exception {
        try (NeovimSession s = start()) {
            assertTrue(s.viewportEnabled(), "on by default");
            s.setBufferLines(IntStream.rangeClosed(1, 200).mapToObj(i -> "line " + i).toList());
            s.setEditorSizeAsync(100, 30);
            waitFor(() -> windowIs(s, 100, 30), "a window of 100 × 30, below Neovim's status line");
            Object lines = s.lua("vim.cmd('normal! ggL') return { vim.fn.line('.'), vim.fn.line('w$') }");
            assertEquals(List.of(30L, 30L), lines, "L is the editor's last line, not the 39th");
        }
    }

    @Test
    void setNumberMakesTheScreenWiderSoThatTheTextStaysTheEditorsWidth() throws Exception {
        try (NeovimSession s = start()) {
            s.setEditorSizeAsync(80, 20);
            waitFor(() -> windowIs(s, 80, 20), "80 × 20");
            s.lua("vim.cmd('set number')"); // as typed, it fires OptionSet; the module tells the session
            waitFor(() -> windowIs(s, 80, 20), "80 columns of text beside the number column");
            assertEquals(84L, s.lua("return vim.o.columns"), "four more columns for the numbers");
        }
    }

    @Test
    void theOptOutKeepsNeovimsFixedScreen() throws Exception {
        try (NeovimSession s = start("--cmd", "let g:neovim4eclipse_viewport = 0")) {
            assertFalse(s.viewportEnabled());
            s.setEditorSizeAsync(100, 30);
            NeovimBarrier.pass(s); // everything queued before, the size included, is done
            assertEquals(List.of(120L, 40L), s.lua("return { vim.o.columns, vim.o.lines }"));
        }
    }
}
