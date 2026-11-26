// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.PinnedPlugins;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FloatWindow;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * noice.nvim with its library nui.nvim, at the commits pinned and vetted in
 * {@code releng/test-plugins.properties}, set up while Neovim starts, as a user's config does: what
 * the README says of it. noice runs only in part. Eclipse's UI draws the command line and the
 * messages (ui2), so noice switches off its own views of those and says so in a line for each;
 * those open no start-up warning ({@code util/StartupErrorReport} matches their exact text, which
 * this test reads from the real plug-in, so a reworded noice fails here instead of opening the
 * warning at users' every start), and are logged at INFO instead. The rest of noice keeps working,
 * drawn as floating windows: a notification ({@code vim.notify}) and its completion menu after
 * {@code Ctrl-N}, which takes the place of Neovim's own popup menu. Neovim's own command line
 * works as usual.
 *
 * <p>Through the session alone: the warning in the editor opens exactly when
 * {@link NeovimSession#startupErrors()} is not empty ({@code editor/StartupErrorNotice}). Skipped
 * without the plug-ins ({@link PinnedPlugins#HOW}).
 */
@Tag("integration")
class NoiceIntegrationTest {

    @Test
    void noiceRunsInPartAndItsLinesOpenNoStartupWarning(@TempDir Path dir) throws Exception {
        TestNeovim.require();
        Optional<Path> noice = PinnedPlugins.find("noice");
        Optional<Path> nui = PinnedPlugins.find("nui");
        assumeTrue(noice.isPresent() && nui.isPresent(),
                "noice and nui are not fetched at their pinned commits: " + PinnedPlugins.HOW);
        Path init = Files.writeString(dir.resolve("init.lua"), PinnedPlugins.noiceInit(noice.get(), nui.get()));
        // --clean with -u: only this init.lua runs, no ShaDa, no folder of the developer's on the runtimepath.
        try (ExpectedLog log = ExpectedLog.of(NeovimConnection.class, Level.INFO);
                NeovimSession s = NeovimSession.start(TestNeovim.PATH, List.of("--clean", "-u", init.toString()))) {
            assertEquals("", s.startupErrors(), "no start-up warning: noice's lines are all left out");
            log.assertLogged(Level.INFO, "noice.nvim switching off its command line and messages in Eclipse");
            // One line for each of the three UI options noice refuses that Eclipse's UI turns on, as
            // Neovim itself reports them (ext_popupmenu stays off: NeovimConnection.uiOptions).
            Object expected = s.lua("local on = {} for _, ext in ipairs({ 'ext_cmdline', 'ext_popupmenu', 'ext_messages' }) do"
                    + " if vim.api.nvim_list_uis()[1][ext] then on[#on + 1] = ext end end return table.concat(on, ' ')");
            Object said = s.lua("local said = {} for line in vim.api.nvim_exec2('messages', { output = true }).output:gmatch('[^\\n]+') do"
                    + " local ext = line:match(\"^You're using a GUI that uses (ext_%a+)%. Noice can't work\")"
                    + " if ext then said[#said + 1] = ext end end return table.concat(said, ' ')");
            assertEquals(expected, said, "noice said why, once for each option Eclipse's UI has on");
            assertEquals(true, s.lua("return require('noice.config').is_running()"), "the rest of noice runs");

            // keep: noice's notification view (its mini view, with no notification plug-in
            // installed) hides a message once its timer runs, unless the message's keep() says
            // to keep it (noice/view/backend/mini.lua, can_hide); this one stays, so the wait for
            // it depends on no timer, however slow the machine.
            s.lua("vim.notify('a notice from the test', vim.log.levels.WARN, { keep = function() return true end })"
                    + " return 0");
            waitFor(() -> drawnFloatWith(s, "a notice from the test", false), "the notification in noice's float");

            s.setBufferLines(List.of("alpha", "alphabet", "al"));
            for (String key : List.of("G", "A", "<C-n>")) {
                s.input(key);
            }
            waitFor(() -> drawnFloatWith(s, "alphabet", false) && !drawnFloatWith(s, "alphabet", true),
                    "the matches in noice's menu, not in Neovim's own");
            waitFor(() -> "ic".equals(s.getMode()), "Neovim completing");
            s.input("<C-e>");
            s.input("<Esc>");
            waitFor(() -> "n".equals(s.getMode()), "Normal mode");

            s.input(":");
            for (char c : "let g:typed = 7".toCharArray()) {
                s.input(String.valueOf(c));
            }
            s.input("<CR>");
            assertEquals(7L, ((Number) s.lua("return vim.g.typed or 0")).longValue(),
                    "Neovim's own command line works");
        }
    }

    /**
     * Whether a float the session reports to be drawn shows {@code text}: Neovim's own popup menu
     * (the grid with no window, -1) when {@code neovimsMenu}, else a window's, a plug-in's.
     */
    private static boolean drawnFloatWith(NeovimSession s, String text, boolean neovimsMenu) {
        for (FloatWindow f : s.floatingWindows()) {
            if ((f.grid().window() == -1) != neovimsMenu) {
                continue;
            }
            for (int r = 0; r < f.grid().height(); r++) {
                if (f.grid().row(r).contains(text)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Waits for the session's state, or fails after {@link TestWait}'s limit. */
    private static void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TestWait.millis();
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }
}
