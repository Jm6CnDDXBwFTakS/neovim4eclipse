// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FoldRegions;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FoldRegions.Region;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
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
        assumeTrue(NvimExecutable.looksAvailable(NVIM), "nvim not available");
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
