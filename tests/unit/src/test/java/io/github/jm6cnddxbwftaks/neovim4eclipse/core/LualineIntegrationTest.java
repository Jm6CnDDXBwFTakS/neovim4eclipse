// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.PinnedPlugins;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * lualine.nvim through the session, at the commit pinned and vetted in
 * {@code releng/test-plugins.properties}: the status line USER_GUIDE names as drawn under the
 * editor. lualine sets {@code 'statusline'}, and the session evaluates it for the window as Neovim
 * would ({@code util/StatusLines}), so what it reports is lualine's line: the mode by lualine's
 * names ({@code NORMAL}, {@code INSERT}, {@code VISUAL}), following each mode change, and the
 * buffer's name. No program starts ({@link PinnedPlugins#REFUSE_PROGRAMS}). The row drawn in a
 * real editor is {@code LualineEndToEndTest}'s, with {@code [+]} once the document changed
 * (Neovim's {@code 'modified'} follows the editor's unsaved-changes marker, so it needs an
 * editor). Skipped without the plug-in ({@link PinnedPlugins#HOW}).
 *
 * <p>The test has a Neovim of its own: lualine's setup acts on the whole Neovim.
 */
@Tag("integration")
class LualineIntegrationTest {

    @Test
    void theStatusLineIsLualinesAndFollowsTheMode() throws Exception {
        TestNeovim.require();
        Optional<Path> lualine = PinnedPlugins.find("lualine");
        assumeTrue(lualine.isPresent(), "lualine is not fetched at its pinned commit: " + PinnedPlugins.HOW);
        try (NeovimSession s = NeovimSession.start(TestNeovim.PATH, List.of("--clean"))) {
            s.lua("vim.api.nvim_buf_set_name(0, 'notes.txt') return 0");
            s.lua(PinnedPlugins.loadLualine(lualine.get()) + " return 0");
            s.setBufferLines(List.of("one", "two"));
            int window = ((Number) s.lua("return vim.api.nvim_get_current_win()")).intValue();

            s.input("j");
            waitForStatusLine(s, window, line -> line.startsWith(" NORMAL ") && line.contains("notes.txt"),
                    "lualine's line in Normal mode, with the buffer's name");
            s.input("i");
            waitForStatusLine(s, window, line -> line.startsWith(" INSERT "), "INSERT, as the mode changed");
            s.input("<Esc>");
            s.input("v");
            waitForStatusLine(s, window, line -> line.startsWith(" VISUAL "), "VISUAL");
            s.input("<Esc>");
            waitForStatusLine(s, window, line -> line.startsWith(" NORMAL "), "NORMAL again");

            assertEquals("", s.lua(PinnedPlugins.PROGRAMS_REFUSED), "lualine asked to start no program");
        }
    }

    /** Waits until window {@code window}'s last status line, its one row, passes {@code check}. */
    private static void waitForStatusLine(NeovimSession s, int window, Predicate<String> check, String what)
            throws InterruptedException {
        BooleanSupplier passes = () -> {
            GridSnapshot line = s.statusLine(window);
            return line != null && line.height() == 1 && check.test(line.row(0));
        };
        long deadline = System.currentTimeMillis() + TestWait.millis();
        while (!passes.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                GridSnapshot line = s.statusLine(window);
                throw new AssertionError("timed out waiting for " + what + "; the status line is "
                        + (line == null || line.height() == 0 ? "none" : "'" + line.row(0) + "'"));
            }
            Thread.sleep(25);
        }
    }
}
