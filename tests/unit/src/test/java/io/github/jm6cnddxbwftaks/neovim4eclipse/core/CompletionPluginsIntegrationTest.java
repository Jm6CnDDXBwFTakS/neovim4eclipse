// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.PinnedPlugins;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FloatWindow;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InsertReplay;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * Real completion plug-ins through the session, at the commits pinned and vetted in
 * {@code releng/test-plugins.properties}: blink.cmp (with its pure-Lua matcher, nothing downloaded)
 * and nvim-cmp with cmp-buffer. Each maps {@code Ctrl-N} in Insert mode, so that key leaves the
 * rest of an insert Eclipse typed to Neovim ({@code KeyInput.mappedInInsert}; docs/USER_GUIDE.md,
 * "Completion", "A completion plug-in's keys"). What only the real plug-ins show: Neovim gets what
 * Eclipse typed, the plug-in's menu opens and is reported to be drawn, the letters typed next
 * narrow it, its {@code Ctrl-E} and {@code Ctrl-Y} cancel and accept, and after {@code Esc} the
 * next insert is Eclipse's again. The same in a real editor, the caret included, is
 * {@code CompletionPluginsEndToEndTest}. Skipped without the plug-ins ({@link PinnedPlugins#HOW}).
 *
 * <p>Each test has a Neovim of its own: a plug-in's {@code setup()} acts on the whole Neovim and
 * cannot be undone.
 */
@Tag("integration")
class CompletionPluginsIntegrationTest {

    private static final List<String> WORDS = List.of("alpha", "alphabet", "alpine", "", "");
    private static final String BEFORE = "alpha\nalphabet\nalpine\n\n\n";
    /** Offset of the fourth line, where the typing happens. */
    private static final int LINE_4 = "alpha\nalphabet\nalpine\n".length();

    @Test
    void blinkCmpsCtrlNLeavesTheInsertToNeovimAndItsMenuFollowsTheTyping() throws Exception {
        TestNeovim.require();
        Optional<Path> blink = PinnedPlugins.find("blink.cmp");
        assumeTrue(blink.isPresent(), "blink.cmp is not fetched at its pinned commit: " + PinnedPlugins.HOW);
        try (NeovimSession s = start()) {
            s.lua(PinnedPlugins.loadBlinkCmp(blink.get()) + " return 0");
            waitFor(() -> "lua".equals(s.lua(PinnedPlugins.BLINK_READY)),
                    "blink.cmp set up with its Lua matcher, no program asked for");
            completesAndHandsBack(s);
            assertEquals("lua", s.lua(PinnedPlugins.BLINK_READY), "still no program asked for");
        }
    }

    @Test
    void nvimCmpsCtrlNLeavesTheInsertToNeovimAndItsMenuFollowsTheTyping() throws Exception {
        TestNeovim.require();
        Optional<Path> cmp = PinnedPlugins.find("nvim-cmp");
        Optional<Path> buffer = PinnedPlugins.find("cmp-buffer");
        assumeTrue(cmp.isPresent() && buffer.isPresent(),
                "nvim-cmp and cmp-buffer are not fetched at their pinned commits: " + PinnedPlugins.HOW);
        try (NeovimSession s = start()) {
            s.lua(PinnedPlugins.loadNvimCmp(cmp.get(), buffer.get()) + " return 0");
            completesAndHandsBack(s);
        }
    }

    /**
     * The steps both plug-ins go through. {@code al} typed by Eclipse, then the plug-in's
     * {@code Ctrl-N}: the menu opens with the buffer's words. Whether a match is put in at once
     * depends on whether the menu was open already, from the re-typed {@code al}, when the key
     * came (both plug-ins then select the next entry; otherwise blink.cmp's key does nothing and
     * nvim-cmp's opens the menu): a matter of timing, so the test does not ask. {@code Ctrl-E} takes it back to {@code al}; then {@code phab}, typed by
     * Neovim since the key, opens the menu again on its own and narrows it to {@code alphabet};
     * {@code Ctrl-Y} accepts that. {@code Esc} ends the insert, and the next one is Eclipse's.
     */
    private static void completesAndHandsBack(NeovimSession s) throws Exception {
        s.completeInNeovimAsync(eclipseTyped(s, "al"), "<C-n>");
        waitFor(() -> menus(s, "alphabet") >= 1 && menus(s, "alpine") >= 1, "the plug-in's menu, with the buffer's words",
                () -> state(s));
        assertTrue(s.isNeovimDrivenInsert(), "Neovim types the rest of the insert");
        waitFor(() -> reported(s, "alphabet"), "the menu reported to be drawn", () -> state(s));

        s.inputAsync("<C-e>");
        waitFor(() -> menus(s, null) == 0 && "al".equals(line(s, 4)), "the menu closed, the match taken back",
                () -> state(s));

        for (String key : List.of("p", "h", "a", "b")) {
            s.inputAsync(key);
        }
        waitFor(() -> "alphab".equals(line(s, 4)), "Neovim typed the letters", () -> state(s));
        waitFor(() -> menus(s, "alphabet") >= 1 && menus(s, "alpine") == 0,
                "the menu opened again by itself, narrowed to alphabet", () -> state(s));
        assertTrue(s.isNeovimDrivenInsert(), "still Neovim's");

        s.inputAsync("<C-y>");
        waitFor(() -> menus(s, null) == 0 && "alphabet".equals(line(s, 4)), "alphabet accepted, the menu closed",
                () -> state(s));
        assertTrue(s.isNeovimDrivenInsert(), "Neovim types on until Esc");

        s.inputAsync("<Esc>");
        waitFor(() -> "n".equals(s.getMode()) && !s.isNeovimDrivenInsert(), "the insert over", () -> state(s));
        assertEquals(List.of("alpha", "alphabet", "alpine", "alphabet", ""), s.getBufferLines());

        s.inputAsync("o");
        waitFor(() -> "i".equals(s.getMode()), "a new insert", () -> state(s));
        assertFalse(s.isNeovimDrivenInsert(), "the next insert is Eclipse's again");
    }

    /**
     * A session whose buffer changes are applied as the editor applies them: each change's
     * {@code afterApply} runs, which is what lets Eclipse type again after a hand-back (the
     * handshake). Without it no insert would ever go back to Eclipse here, and the tests could not
     * tell an insert left to Neovim from one waiting to be handed back.
     */
    private static NeovimSession start() throws Exception {
        NeovimSession s = NeovimSession.start(TestNeovim.PATH, List.of("--clean"));
        s.addBufferChangeListener(change -> {
            if (change.afterApply() != null) {
                change.afterApply().run();
            }
        });
        return s;
    }

    /**
     * The words in the buffer, the cursor on the fourth line in an insert Eclipse types, and
     * {@code typed} typed there by Eclipse: handed to Neovim at the next sync point, as
     * {@code NeovimCompletionIntegrationTest} does (the push that follows sets nothing here:
     * {@code setBufferLines} goes through {@code LuaScripts.SET_LINES_MARKED}, which leaves equal
     * lines alone).
     *
     * <p>The buffer is made {@code acwrite}, as an editor's document buffer is
     * ({@code core/lua/document_buffer.lua}): the session's first buffer is {@code nofile}, whose
     * words blink.cmp's buffer source leaves out.
     */
    private static InsertReplay.SyncPoint eclipseTyped(NeovimSession s, String typed) throws Exception {
        s.lua("vim.bo.buftype = 'acwrite' return 0");
        s.setBufferLines(WORDS);
        for (String key : List.of("4", "G", "i")) {
            s.input(key);
        }
        assertFalse(s.isNeovimDrivenInsert(), "Eclipse types this insert");
        List<String> document = new ArrayList<>(WORDS);
        document.set(3, typed);
        s.setLeaveInsertPush(() -> {
            try {
                s.setBufferLines(document);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        return InsertReplay.syncPoint(BEFORE, BEFORE.substring(0, LINE_4) + typed + BEFORE.substring(LINE_4), LINE_4,
                LINE_4 + typed.length());
    }

    /** {@link PinnedPlugins#COMPLETION_MENUS}' answer, with {@code word} (a plain word of the test's own) or none. */
    private static int menus(NeovimSession s, String word) throws Exception {
        Object n = s.lua("return (function(...)\n" + PinnedPlugins.COMPLETION_MENUS + "\nend)("
                + (word == null ? "" : "'" + word + "'") + ")");
        return ((Number) n).intValue();
    }

    /** Whether a float the session reports to be drawn has a row holding {@code word}. */
    private static boolean reported(NeovimSession s, String word) {
        for (FloatWindow f : s.floatingWindows()) {
            for (int r = 0; r < f.grid().height(); r++) {
                if (f.grid().row(r).contains(word)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** What a timed-out wait says: Neovim's mode, line 4, the menus' lines, its last error. */
    private static String state(NeovimSession s) {
        try {
            return "Neovim: " + s.lua("local m = {} for _, w in ipairs(vim.api.nvim_list_wins()) do"
                    + " local b = vim.api.nvim_win_get_buf(w) if vim.api.nvim_win_get_config(w).relative ~= '' then"
                    + " m[#m + 1] = vim.bo[b].filetype .. ':' .. table.concat(vim.api.nvim_buf_get_lines(b, 0, -1, false), '/')"
                    + " end end return 'mode ' .. vim.api.nvim_get_mode().mode .. ', line 4 ' .. vim.inspect(vim.fn.getline(4))"
                    + " .. ', floats ' .. table.concat(m, ' | ') .. ', errmsg ' .. vim.v.errmsg")
                    + "; driven " + s.isNeovimDrivenInsert();
        } catch (Exception e) {
            return "Neovim did not answer: " + e;
        }
    }

    private static String line(NeovimSession s, int line1) throws Exception {
        return s.getBufferLines().get(line1 - 1);
    }

    private interface Check {
        boolean holds() throws Exception;
    }

    /** Waits for Neovim's or the session's state, read with RPC calls that may fail while it changes. */
    private static void waitFor(Check condition, String what) throws Exception {
        waitFor(condition, what, () -> "");
    }

    /** As {@link #waitFor(Check, String)}, saying {@code state} when it times out. */
    private static void waitFor(Check condition, String what, java.util.function.Supplier<String> state) throws Exception {
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
                throw new AssertionError("timed out waiting for " + what + "; " + state.get());
            }
            Thread.sleep(25);
        }
    }
}
