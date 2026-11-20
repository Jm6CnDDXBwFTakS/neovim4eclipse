// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FoldRegions;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FoldRegions.Region;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * Eclipse's folds in a real Neovim through the session (docs/design/FOLDING.md): the opt-out read at
 * start, {@link NeovimSession#setFoldsAsync}, and the closed folds heard after keys sent one at a
 * time, as Eclipse sends them. What the Lua does in detail is {@code folds_spec.lua}; here only what
 * needs the session: the start-up read, the input thread, the snapshot's second flag and the
 * listener. Skipped when no {@code nvim} is found.
 */
@Tag("integration")
class NeovimFoldsIntegrationTest {

    private static final String NVIM = TestNeovim.PATH;

    /** A class 1-20 (index 0) with methods 3-8 (1) and 10-15 (2), all open. */
    private static final List<FoldRegions.Fold> CLASS = FoldRegions.forNeovim(List.of(
            new Region(1, 20, false), new Region(3, 8, false), new Region(10, 15, false)));

    private static NeovimSession start(String... extraArgs) throws Exception {
        TestNeovim.require();
        List<String> args = new ArrayList<>(List.of("--clean"));
        args.addAll(List.of(extraArgs));
        return NeovimSession.start(NVIM, args);
    }

    private static void waitFor(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + TestWait.millis();
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    /** A shown document buffer with 30 numbered lines and the class's folds sent. */
    private static DocumentBuffer documentWithFolds(NeovimSession s) throws Exception {
        DocumentBuffer doc = new DocumentBuffer("");
        s.showBuffer(doc);
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            lines.add("line " + i);
        }
        s.setBufferLines(lines);
        s.setFoldsAsync(doc, CLASS);
        waitFor(() -> mirrored(s), "the folds to arrive");
        return doc;
    }

    private static boolean mirrored(NeovimSession s) {
        try {
            return Boolean.TRUE.equals(s.lua("return vim.b.neovim4eclipse_folds == true"));
        } catch (Exception e) {
            return false;
        }
    }

    private static void keys(NeovimSession s, String... keys) throws Exception {
        for (String key : keys) {
            s.input(key);
        }
    }

    private static int cursorLine(NeovimSession s) throws Exception {
        return ((Number) s.lua("return vim.fn.line('.')")).intValue();
    }

    /**
     * As at an editor's attach: the push that creates the buffer is queued, and the folds right
     * after it, before the buffer has a number. The folds must still reach that buffer.
     */
    @Test
    void foldsQueuedBehindThePushThatCreatesTheBufferArrive() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer doc = new DocumentBuffer("");
            s.runAttachSetup(() -> {
                try {
                    s.showBuffer(doc);
                    s.setBufferLines(List.of("a", "b", "c", "d"));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            s.setFoldsAsync(doc, FoldRegions.forNeovim(List.of(new Region(2, 3, true))));
            waitFor(() -> mirrored(s), "the folds to arrive");
            assertEquals(2, ((Number) s.lua("return vim.fn.foldclosed(3)")).intValue());
        }
    }

    @Test
    void zcAndZoChangeTheClosedFoldsHeardAfterTheKey() throws Exception {
        try (NeovimSession s = start()) {
            assertTrue(s.foldsEnabled());
            DocumentBuffer doc = documentWithFolds(s);
            List<FoldState> heard = new CopyOnWriteArrayList<>();
            s.addFoldStateListener(heard::add);

            keys(s, "3", "G", "z", "c");
            assertEquals(new FoldState(doc.bufnr(), List.of(1)), heard.get(heard.size() - 1));

            keys(s, "z", "M");
            assertEquals(List.of(0), heard.get(heard.size() - 1).closed(), "the methods inside are not listed");

            keys(s, "z", "R");
            assertEquals(List.of(), heard.get(heard.size() - 1).closed());
        }
    }

    @Test
    void jStepsOverAClosedFoldAndASearchOpensIt() throws Exception {
        // Vim's default 'foldopen' (the user's, untouched): j does not open a fold, / does.
        try (NeovimSession s = start()) {
            documentWithFolds(s);
            keys(s, "1", "0", "G", "z", "c", "2", "G");
            keys(s, "j", "j");
            assertEquals(4, cursorLine(s), "j into the open method 3-8 is fine");
            keys(s, "1", "0", "G", "k", "j", "j");
            assertEquals(16, cursorLine(s), "the closed fold 10-15 is one line for j");
            keys(s, "g", "g", "/", "l", "i", "n", "e", " ", "1", "2", "\r");
            assertEquals(12, cursorLine(s));
            assertEquals(-1, ((Number) s.lua("return vim.fn.foldclosed(12)")).intValue(), "the search opened it");
        }
    }

    @Test
    void foldsMoveWithLinesAddedAbove() throws Exception {
        try (NeovimSession s = start()) {
            documentWithFolds(s);
            keys(s, "1", "0", "G", "z", "c");
            keys(s, "g", "g", "O", "\u001b"); // one line above everything, from Neovim
            List<FoldState> heard = new CopyOnWriteArrayList<>();
            s.addFoldStateListener(heard::add);
            keys(s, "1", "1", "G");
            assertEquals(List.of(2), heard.get(heard.size() - 1).closed());
            assertEquals(11, ((Number) s.lua("return vim.fn.foldclosed(11)")).intValue());
        }
    }

    /**
     * A person pausing between {@code z} and {@code E} longer than {@code 'timeoutlen'} (the
     * Neovim's default, 1000 ms): with mappings of the two keys, Neovim gave up waiting, ran its own
     * {@code zE} and deleted every fold. After the pause {@code z} is Neovim's own pending command,
     * and the key watcher drops the {@code E} typed then. The pause is the point of this test, so it
     * sleeps: it is not a wait for an outcome (AGENTS.md, "Rules the code depends on").
     */
    @Test
    void aSlowZEIsRefusedToo() throws Exception {
        try (NeovimSession s = start()) {
            documentWithFolds(s);
            Object levels = s.lua("local r = {} for l = 1, 16 do r[#r+1] = vim.fn.foldlevel(l) end return table.concat(r)");
            long pause = ((Number) s.lua("return vim.o.timeoutlen")).longValue() + 200;
            keys(s, "3", "G", "z");
            Thread.sleep(pause);
            keys(s, "E");
            assertEquals(levels, s.lua("local r = {} for l = 1, 16 do r[#r+1] = vim.fn.foldlevel(l) end return table.concat(r)"),
                    "every fold is still there");
            waitFor(() -> "zE: Eclipse defines the folds in this editor".equals(errmsg(s)), "the refusal said");
        }
    }

    // ---- the key watcher: a fold key typed while z is Neovim's own pending command ----
    // Tested here and not in folds_spec.lua: the watcher reads Neovim's pending command as 'showcmd'
    // shows it, which Neovim keeps only while a UI is attached, and the Lua specs have none.

    /**
     * Takes away the mappings of the two keys, so that {@code z} is Neovim's own pending command at
     * once, as it is after {@code 'timeoutlen'} when the keys are typed apart; no pause needed.
     */
    private static void withoutTheMappings(NeovimSession s) throws Exception {
        s.lua("""
                for _, key in ipairs({ 'f', 'F', 'd', 'D', 'E' }) do
                  vim.keymap.del('n', 'z' .. key, { buffer = 0 })
                  vim.keymap.del('x', 'z' .. key, { buffer = 0 })
                end
                """);
    }

    private static String foldLevels(NeovimSession s) throws Exception {
        return String.valueOf(s.lua("local r = {} for l = 1, 30 do r[#r+1] = vim.fn.foldlevel(l) end return table.concat(r)"));
    }

    @Test
    void aFoldKeyAfterNeovimsOwnPendingZIsDroppedWithACountAndARegisterToo() throws Exception {
        try (NeovimSession s = start()) {
            documentWithFolds(s);
            withoutTheMappings(s);
            String levels = foldLevels(s);
            for (String typed : List.of("zE", "3Gzd", "3GzD", "25Gzfj", "25G2zF", "\"azE", "\"a3zE")) {
                s.lua("vim.v.errmsg = ''");
                keys(s, typed.split(""));
                String key = typed.replaceAll(".*z(.).*", "$1");
                waitFor(() -> ("z" + key + ": Eclipse defines the folds in this editor").equals(errmsg(s)),
                        typed + " refused; v:errmsg is " + errmsg(s));
                assertEquals(levels, foldLevels(s), typed + " left every fold as it was");
                assertEquals("n", s.lua("return vim.api.nvim_get_mode().mode"), typed);
            }
        }
    }

    @Test
    void inVisualModeTheKeyIsDroppedAndVisualModeLeftAndOtherKeysAfterZAreNeovims() throws Exception {
        try (NeovimSession s = start()) {
            documentWithFolds(s);
            withoutTheMappings(s);
            String levels = foldLevels(s);
            keys(s, "2", "5", "G", "V", "j", "z", "f");
            waitFor(() -> "zf: Eclipse defines the folds in this editor".equals(errmsg(s)), "zf refused in visual mode");
            assertEquals(levels, foldLevels(s), "no fold made");
            assertEquals("n", s.lua("return vim.api.nvim_get_mode().mode"), "visual mode left, as a refused zf does");
            for (String key : List.of("E", "F", "d", "D")) {
                s.lua("vim.v.errmsg = ''");
                keys(s, "3", "G", "V", "j", "z", key);
                waitFor(() -> ("z" + key + ": Eclipse defines the folds in this editor").equals(errmsg(s)),
                        "z" + key + " refused in visual mode");
                assertEquals(levels, foldLevels(s), "z" + key + " changed no fold");
                assertEquals("n", s.lua("return vim.api.nvim_get_mode().mode"), "z" + key);
            }
            keys(s, "3", "G", "z", "c");
            assertEquals(3, ((Number) s.lua("return vim.fn.foldclosed(3)")).intValue(), "zc is Neovim's");
            keys(s, "z", "o");
            assertEquals(-1, ((Number) s.lua("return vim.fn.foldclosed(3)")).intValue());
        }
    }

    /** The same keys where {@code z} is not the pending command: a search, a replace, a register, typed text. */
    @Test
    void theSameKeysAreNotDroppedWhereZIsNotThePendingCommand() throws Exception {
        try (NeovimSession s = start()) {
            documentWithFolds(s);
            withoutTheMappings(s);
            s.lua("vim.api.nvim_buf_set_lines(0, 24, 26, false, { 'abc zE xyz', 'second' })");
            keys(s, "2", "5", "G", "0", "f", "E");
            assertEquals(6, ((Number) s.lua("return vim.fn.col('.')")).intValue(), "fE found the E");
            keys(s, "r", "d");
            assertEquals("abc zd xyz", s.lua("return vim.fn.getline(25)"), "rd replaced it with d");
            keys(s, "\"", "z", "d", "d");
            assertEquals("second", s.lua("return vim.fn.getline(25)"), "dd after the register z deleted the line");
            assertEquals("abc zd xyz\n", s.lua("return vim.fn.getreg('z')"));
            keys(s, "0", "i", "f", "E", "<Esc>");
            assertEquals("fEsecond", s.lua("return vim.fn.getline(25)"), "typed text in insert mode");
            assertEquals("", errmsg(s));
        }
    }

    /**
     * One command from insert mode ({@code Ctrl-O}) is normal mode to Neovim (mode {@code niI}):
     * a fold key after its pending {@code z} is dropped there too, and insert mode goes on.
     */
    @Test
    void afterCtrlOInInsertModeTheKeyIsDroppedTooAndInsertModeGoesOn() throws Exception {
        try (NeovimSession s = start()) {
            documentWithFolds(s);
            withoutTheMappings(s);
            String levels = foldLevels(s);
            keys(s, "2", "5", "G", "i", "<C-o>", "z", "E");
            waitFor(() -> "zE: Eclipse defines the folds in this editor".equals(errmsg(s)), "zE refused after Ctrl-O");
            assertEquals(levels, foldLevels(s), "every fold is still there");
            assertEquals("i", s.lua("return vim.api.nvim_get_mode().mode"));
            keys(s, "<Esc>");
        }
    }

    /**
     * A key typed right behind the dropped one is Neovim's own, not the argument of the {@code z}
     * that was pending: the {@code Esc} that ends the {@code z} goes in front of it. Example:
     * {@code z}, then {@code E} and {@code j} sent together; the cursor goes down a line, where
     * {@code zj} would have gone to the next fold.
     */
    @Test
    void aKeySentTogetherWithTheDroppedOneIsNotTakenAsTheArgumentOfZ() throws Exception {
        try (NeovimSession s = start()) {
            documentWithFolds(s);
            withoutTheMappings(s);
            keys(s, "2", "0", "G", "z");
            keys(s, "Ek"); // one nvim_input with both keys
            assertEquals(19, ((Number) s.lua("return vim.fn.line('.')")).intValue(), "k moved up a line; zk would go to line 15");
        }
    }

    /**
     * Folds that Eclipse sends while {@code z} is pending wait for the next key: Neovim answers no
     * request until then, and asking would hold the input thread, and that key behind it, for the
     * RPC limit, after which the send fails. Held, it fails nothing and arrives after the key.
     */
    @Test
    void foldsSentWhileZIsPendingWaitForTheNextKeyAndArrive() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(InputThread.class, Level.FINE); NeovimSession s = start()) {
            DocumentBuffer doc = documentWithFolds(s);
            // All three through the session's one input thread, as the editor sends them, so the
            // order is fixed: z, the folds, j. (A key sent from this thread would race the folds.)
            s.inputAsync("z");
            s.setFoldsAsync(doc, FoldRegions.forNeovim(List.of(new Region(1, 20, false)))); // the two methods are gone
            s.inputAsync("j");
            waitFor(() -> "0".equals(String.valueOf(luaOrNull(s, "return vim.fn.foldlevel(5) - 1"))), "the new folds applied");
            log.assertNotLoggedAtOrAbove(Level.FINE, "folds for");
        }
    }

    private static Object luaOrNull(NeovimSession s, String code) {
        try {
            return s.lua(code);
        } catch (Exception e) {
            return null;
        }
    }

    private static String errmsg(NeovimSession s) {
        try {
            return String.valueOf(s.lua("return vim.v.errmsg"));
        } catch (Exception e) {
            return e.toString();
        }
    }

    @Test
    void theOptOutTurnsFoldingOffAndTheUsersOwnFoldsStay() throws Exception {
        // As an init.lua would: set before the session reads it, and a global foldmethod of the user's.
        try (NeovimSession s = start("--cmd", "let g:neovim4eclipse_folds = 0 | set foldmethod=indent shiftwidth=4")) {
            assertFalse(s.foldsEnabled());
            DocumentBuffer doc = new DocumentBuffer("");
            s.showBuffer(doc);
            s.setBufferLines(List.of("class A {", "    int x;", "    int y;", "}"));
            List<FoldState> heard = new CopyOnWriteArrayList<>();
            s.addFoldStateListener(heard::add);
            s.setFoldsAsync(doc, CLASS); // ignored
            keys(s, "j");
            NeovimBarrier.pass(s);

            assertEquals(List.of(), heard, "no fold state is read");
            assertEquals("indent", s.lua("return vim.wo.foldmethod"));
            assertEquals(Boolean.FALSE, s.lua("return package.loaded['neovim4eclipse.folds'] ~= nil"));
        }
    }

    @Test
    void foldingIsOnWhenTheVariableIsUnsetOrOne() throws Exception {
        try (NeovimSession s = start("--cmd", "let g:neovim4eclipse_folds = 1")) {
            assertTrue(s.foldsEnabled());
        }
    }
}
