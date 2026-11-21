// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * An insert that begins in a plug-in's focused floating window (a picker's prompt) is Neovim's to
 * its end, with a real Neovim and the keys routed as the editor routes them ({@link KeyRouter}):
 * every key typed after the one that opened the picker reaches Neovim, Eclipse never types any of
 * them, and the insert is not handed back to Eclipse until it ends. The pure decisions are
 * {@code ModeTransitionTest} and {@code KeyInputTest}; SETUP's report is {@code windows_spec.lua}.
 *
 * <p>The picker is a command of the test's own, mapped to {@code gp} and {@code gP}, that opens
 * its prompt as a picker does: a scratch prompt buffer in a float, entered, then
 * {@code startinsert}, run within the command ({@code gp}) or from a {@code vim.schedule} callback
 * ({@code gP}), or in Telescope's order ({@code F6}: the float opened without the focus, entered,
 * made a prompt, then {@code nvim_feedkeys("A")}). In the second case Neovim reports the
 * insert only after the key that opened the picker was answered, so only the report decides.
 *
 * <p>Held back: the WinNew safety net of {@link LuaScripts#WINDOWS}, which today closes such a float
 * right away, runs as in the product, but its close of the test's prompt is refused (a stand-in for
 * {@code nvim_win_close} in this Neovim), so that the prompt stays open while the keys arrive. The
 * session leaves Neovim in the new prompt ({@code util/EnterDecision}, {@code new_float}), so the
 * keys must end up in the prompt, and the document must keep its text.
 *
 * <p>Each case runs {@link #ROUNDS} times in one Neovim, the keys routed without a pause; on a loaded
 * machine run it pinned to two cores (CONTRIBUTING.md, "Tests on a loaded machine").
 */
@Tag("integration")
class FloatInsertIntegrationTest {

    private static final String NVIM = TestNeovim.PATH;
    private static final long WAIT_MS = TestWait.millis();
    private static final int ROUNDS = 25;

    /**
     * The test's picker: {@code :TestPicker} starts its insert in the command, {@code :TestPicker
     * later} from vim.schedule, {@code :TestPicker telescope} in Telescope's order.
     */
    private static final String PICKER = """
            -- The net still runs (its pass is what lets the session leave Neovim in a new float),
            -- but cannot close the test's prompt; CLOSE ends the round with the real close.
            local close = vim.api.nvim_win_close
            _G.n4e_real_close = close
            vim.api.nvim_win_close = function(win, force)
              if vim.api.nvim_win_is_valid(win) and vim.api.nvim_win_get_buf(win) == vim.g.n4e_prompt then
                error('the test keeps its prompt open')
              end
              return close(win, force)
            end
            vim.api.nvim_create_user_command('TestPicker', function(o)
              local buf = vim.api.nvim_create_buf(false, true)
              local opts = { relative = 'editor', row = 1, col = 1, width = 30, height = 1 }
              vim.g.n4e_prompt = buf
              if o.args == 'telescope' then
                -- Telescope's order: plenary's popup opens the scratch buffer's window without the
                -- focus and enters it; Telescope then makes it a prompt and types A.
                vim.api.nvim_set_current_win(vim.api.nvim_open_win(buf, false, opts))
                vim.bo[buf].buftype = 'prompt'
                vim.fn.prompt_setprompt(buf, '> ')
                vim.api.nvim_feedkeys('A', 'ni', true)
                return
              end
              vim.bo[buf].buftype = 'prompt'
              vim.fn.prompt_setprompt(buf, '> ')
              vim.api.nvim_open_win(buf, true, opts)
              if o.args == 'later' then
                vim.schedule(function() vim.cmd.startinsert() end)
              else
                vim.cmd.startinsert()
              end
            end, { nargs = '?' })
            -- Opened from a mapping, as pickers are (<leader>ff).
            vim.keymap.set('n', 'gp', '<Cmd>TestPicker<CR>')
            vim.keymap.set('n', 'gP', '<Cmd>TestPicker later<CR>')
            vim.keymap.set('n', '<F6>', '<Cmd>TestPicker telescope<CR>')
            return 0
            """;

    /** Ends the round: normal mode, every float closed, Neovim in the editor's window again. */
    private static final String CLOSE = """
            vim.cmd.stopinsert()
            for _, w in ipairs(vim.api.nvim_list_wins()) do
              if vim.fn.win_gettype(w) == 'popup' then pcall(_G.n4e_real_close, w, true) end
            end
            return 0
            """;

    @ParameterizedTest(name = "insert begun {0}")
    @ValueSource(strings = { "within the command", "later", "telescope" })
    void keysTypedRightAfterThePickerOpensAllGoToNeovim(String when) throws Exception {
        TestNeovim.require();
        try (NeovimSession s = NeovimSession.start(NVIM, List.of("--clean"))) {
            // Wrong whenever it is true: Eclipse may type in an insert while the picker is open.
            List<String> eclipseMayType = new CopyOnWriteArrayList<>();
            s.addBufferChangeListener(change -> {
                if (change.afterApply() != null) {
                    change.afterApply().run(); // as the editor does once it applied the change
                }
            });
            s.setBufferLines(List.of("one"));
            s.lua(PICKER);
            KeyRouter router = new KeyRouter(s);
            List<String> opening = switch (when) {
                case "later" -> List.of("g", "P");
                case "telescope" -> List.of("<F6>");
                default -> List.of("g", "p");
            };
            for (int round = 0; round < ROUNDS; round++) {
                List<String> keys = new java.util.ArrayList<>(opening);
                keys.addAll(List.of("a", "b", "c"));
                CountDownLatch handled = new CountDownLatch(keys.size());
                int at = round;
                Runnable listener = () -> {
                    // After each key Neovim handled, the scheduled insert's report included.
                    if (s.isTypingMode() && s.isEclipseTypingReady() && !s.isNeovimDrivenInsert()) {
                        eclipseMayType.add("round " + at + " in mode " + s.getMode());
                    }
                    handled.countDown();
                };
                s.addInputHandledListener(listener);
                try {
                    for (String key : keys) {
                        assertTrue(router.route(key), "round " + round + ": " + key + " goes to Neovim");
                        if (s.isTypingMode() && s.isEclipseTypingReady() && !s.isNeovimDrivenInsert()) {
                            eclipseMayType.add("round " + round + " after " + key);
                        }
                    }
                    assertTrue(handled.await(WAIT_MS, TimeUnit.MILLISECONDS), "round " + round + ": Neovim handled every key");
                } finally {
                    s.removeInputHandledListener(listener);
                }
                long deadline = System.currentTimeMillis() + WAIT_MS;
                while (!"i".equals(s.getMode()) && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10); // the scheduled startinsert's report
                }
                assertEquals("i", s.getMode(), "round " + round);
                assertTrue(s.isNeovimDrivenInsert(), "round " + round + ": the insert is Neovim's");
                // (Eclipse may look ready, after the insert's sync, as in a block insert: KeyRouter asks
                // isNeovimDrivenInsert first, and while it holds, every key is Neovim's.)
                assertEquals("> abc", String.valueOf(s.lua("return vim.api.nvim_buf_get_lines(vim.g.n4e_prompt, 0, -1, false)[1]")),
                        "round " + round + ": the prompt has the keys, in order");
                assertEquals(List.of("one"), s.getBufferLines(), "round " + round + ": the document keeps its text");
                s.lua(CLOSE);
                s.setBufferLines(List.of("one"));
                long settled = System.currentTimeMillis() + WAIT_MS;
                while ((s.isTypingMode() || s.isNeovimDrivenInsert()) && System.currentTimeMillis() < settled) {
                    Thread.sleep(10);
                }
                assertFalse(s.isNeovimDrivenInsert(), "round " + round + ": the insert ended");
            }
            assertTrue(eclipseMayType.isEmpty(), "Eclipse could type during the picker's insert: " + eclipseMayType);

            // Back in the editor's window, an insert is Eclipse's again.
            assertTrue(router.route("A"));
            long deadline = System.currentTimeMillis() + WAIT_MS;
            while (!s.isEclipseTypingReady() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(s.isEclipseTypingReady(), "Eclipse types the editor's insert");
            assertFalse(s.isNeovimDrivenInsert());
        }
    }
}
