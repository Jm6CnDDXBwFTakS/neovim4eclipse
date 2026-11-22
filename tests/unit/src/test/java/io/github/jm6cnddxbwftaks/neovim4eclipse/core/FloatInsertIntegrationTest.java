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
 * <p>As in the product: the WinNew safety net of {@link LuaScripts#WINDOWS} leaves the prompt
 * open while floats are drawn, and the session leaves Neovim in it ({@code util/EnterDecision},
 * {@code entered_float}), so the keys must end up in the prompt, and the document must keep its
 * text. Each round ends as leaving the editor does ({@link NeovimSession#leaveFloatAsync}): the
 * prompt closed, its insert ended.
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

    /**
     * A plug-in's input float that closes itself as Neovim leaves it (a 'nofile' buffer closed on
     * BufLeave, as input plug-ins do): Neovim goes on to another part's window still in the
     * float's insert. Leaving with that float named ends the insert all the same.
     */
    @org.junit.jupiter.api.Test
    void anInsertCarriedOutOfAFloatThatClosedItselfEndsAsTheFloatIsLeft() throws Exception {
        TestNeovim.require();
        try (NeovimSession s = NeovimSession.start(NVIM, List.of("--clean"))) {
            s.setBufferLines(List.of("one"));
            s.lua("""
                    vim.keymap.set('n', 'gS', function()
                      local buf = vim.api.nvim_create_buf(false, true)
                      local win = vim.api.nvim_open_win(buf, true, { relative = 'editor', row = 1, col = 1, width = 20, height = 1 })
                      vim.api.nvim_create_autocmd('BufLeave', { buffer = buf, once = true, callback = function()
                        vim.schedule(function() pcall(vim.api.nvim_win_close, win, true) end)
                      end })
                      vim.cmd.startinsert()
                    end)
                    return 0
                    """);
            int editorWin = s.getAttachedWinid();
            KeyRouter router = new KeyRouter(s);
            assertTrue(router.route("g"));
            assertTrue(router.route("S"));
            long deadline = System.currentTimeMillis() + WAIT_MS;
            while (!(s.inPluginFloat() && "i".equals(s.getMode())) && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            int input = s.pluginFloat();
            assertTrue(input != -1, "in the input float");
            s.lua("vim.api.nvim_set_current_win(" + editorWin + ") return 0"); // the part that took the keyboard
            deadline = System.currentTimeMillis() + WAIT_MS;
            while (!"0".equals(String.valueOf(s.lua("return vim.api.nvim_win_is_valid(" + input + ") and 1 or 0")))
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals("i", String.valueOf(s.lua("return vim.api.nvim_get_mode().mode")), "the insert went on with Neovim");
            s.leaveFloatAsync(input);
            deadline = System.currentTimeMillis() + WAIT_MS;
            while (!"n".equals(String.valueOf(s.lua("return vim.api.nvim_get_mode().mode"))) && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals("n", String.valueOf(s.lua("return vim.api.nvim_get_mode().mode")), "the float's insert ended");
            assertEquals(List.of("one"), s.getBufferLines());
        }
    }

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
                s.lua("vim.api.nvim_win_set_cursor(0, {1, 2}) return 0"); // the editor's caret on the e of "one"
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
                s.leaveFloatAsync(); // as a click away from the editor
                long left = System.currentTimeMillis() + WAIT_MS;
                while (s.inPluginFloat() && System.currentTimeMillis() < left) {
                    Thread.sleep(10);
                }
                assertFalse(s.inPluginFloat(), "round " + round + ": the prompt closed");
                assertEquals("0", String.valueOf(s.lua("return vim.fn.bufwinid(vim.g.n4e_prompt) == -1 and 0 or 1")),
                        "round " + round + ": no window shows the prompt");
                s.setBufferLines(List.of("one"));
                long settled = System.currentTimeMillis() + WAIT_MS;
                while ((s.isTypingMode() || s.isNeovimDrivenInsert()) && System.currentTimeMillis() < settled) {
                    Thread.sleep(10);
                }
                assertFalse(s.isNeovimDrivenInsert(), "round " + round + ": the insert ended");
                assertEquals("2", String.valueOf(s.lua("return vim.api.nvim_win_get_cursor(0)[2]")),
                        "round " + round + ": the editor's cursor where it was (the prompt's Esc moved it not)");
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
