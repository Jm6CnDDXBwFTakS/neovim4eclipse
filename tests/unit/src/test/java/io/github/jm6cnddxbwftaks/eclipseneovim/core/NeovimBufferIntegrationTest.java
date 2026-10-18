// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.BufferChange;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.DocumentBuffer;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.NvimExecutable;

/** One Neovim buffer per Eclipse document, against a real {@code nvim} (skipped when none is found). */
@Tag("integration")
class NeovimBufferIntegrationTest {

    private static final String NVIM = System.getProperty("nvim.path", "nvim");

    private static NeovimSession start() throws Exception {
        assumeTrue(NvimExecutable.looksAvailable(NVIM), "nvim not available");
        return NeovimSession.start(NVIM, List.of("--clean"));
    }

    private static void waitFor(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    private static int currentBufnr(NeovimSession s) throws Exception {
        return ((Number) s.lua("return vim.api.nvim_get_current_buf()")).intValue();
    }

    /** Loading the file is not an undo step; later pushes (a format, Eclipse's undo) are. */
    @Test
    void undoRightAfterOpeningKeepsTheLoadedText() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer doc = new DocumentBuffer("");
            s.showBuffer(doc);
            s.setBufferLines(List.of("x", "y"));
            s.input("u");
            assertEquals(List.of("x", "y"), s.getBufferLines());
            s.setBufferLines(List.of("x", "z"));
            s.input("u");
            assertEquals(List.of("x", "y"), s.getBufferLines());
        }
    }

    @Test
    void eachDocumentKeepsItsOwnUndoHistory() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer a = new DocumentBuffer("");
            DocumentBuffer b = new DocumentBuffer("");
            s.showBuffer(a);
            s.setBufferLines(List.of("a1", "a2"));
            s.input("d");
            s.input("d");
            s.showBuffer(b);
            s.setBufferLines(List.of("b1"));
            s.showBuffer(a);
            assertEquals(List.of("a2"), s.getBufferLines());
            s.input("u");
            assertEquals(List.of("a1", "a2"), s.getBufferLines());
        }
    }

    @Test
    void pushingUnchangedTextAddsNoUndoStep() throws Exception {
        try (NeovimSession s = start()) {
            s.showBuffer(new DocumentBuffer(""));
            s.setBufferLines(List.of("x", "y"));
            s.input("d");
            s.input("d");
            s.setBufferLines(List.of("y")); // what a push after the change looks like
            s.input("u");
            assertEquals(List.of("x", "y"), s.getBufferLines());
        }
    }

    @Test
    void pushReplacesOnlyTheChangedLinesSoMarksElsewhereSurvive() throws Exception {
        try (NeovimSession s = start()) {
            s.showBuffer(new DocumentBuffer(""));
            s.setBufferLines(List.of("one", "two", "three"));
            s.input("m");
            s.input("a"); // mark a on line 1
            s.setBufferLines(List.of("one", "TWO", "three"));
            Object markLine = s.lua("return vim.api.nvim_buf_get_mark(0, 'a')[1]");
            assertEquals(1, ((Number) markLine).intValue());
        }
    }

    @Test
    void lineEventsOfOtherBuffersAreNotForwarded() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer a = new DocumentBuffer("");
            DocumentBuffer b = new DocumentBuffer("");
            s.showBuffer(a);
            s.setBufferLines(List.of("a"));
            s.showBuffer(b);
            s.setBufferLines(List.of("b1", "b2"));
            List<BufferChange> changes = new CopyOnWriteArrayList<>();
            s.addBufferChangeListener(changes::add);

            s.lua("vim.api.nvim_buf_set_lines(" + a.bufnr() + ", 0, -1, false, {'changed elsewhere'})");
            s.input("d");
            s.input("d");
            waitFor(() -> !changes.isEmpty(), "the attached buffer's change");
            Thread.sleep(200); // give a wrongly forwarded event time to arrive
            assertTrue(changes.stream().noneMatch(c -> c.newLines().contains("changed elsewhere")), changes.toString());
        }
    }

    @Test
    void neovimSwitchingToAnotherDocumentIsReportedButEclipsesOwnSwitchesAreNot() throws Exception {
        try (NeovimSession s = start()) {
            List<Integer> entered = new CopyOnWriteArrayList<>();
            s.setBufferEnterHandler(entered::add);
            DocumentBuffer a = new DocumentBuffer("");
            DocumentBuffer b = new DocumentBuffer("");
            s.showBuffer(a);
            s.showBuffer(b);
            Thread.sleep(200);
            assertTrue(entered.isEmpty(), "Eclipse's own switches: " + entered);

            s.command("buffer " + a.bufnr());
            waitFor(() -> entered.contains(a.bufnr()), "the jump to the other document");
        }
    }

    @Test
    void enteringABufferThatIsNoDocumentSwitchesBack() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferEnterHandler(bufnr -> { });
            DocumentBuffer a = new DocumentBuffer("");
            s.showBuffer(a);
            s.command("enew");
            waitFor(() -> {
                try {
                    return currentBufnr(s) == a.bufnr();
                } catch (Exception e) {
                    return false;
                }
            }, "Neovim to return to the attached buffer");
        }
    }

    @Test
    void documentBuffersAreNamedListedAndNeverWrittenByNeovim() throws Exception {
        try (NeovimSession s = start()) {
            String name = System.getProperty("java.io.tmpdir") + "/eclipse-neovim-test/Example.java";
            DocumentBuffer a = new DocumentBuffer(name);
            s.showBuffer(a);
            int buf = a.bufnr();
            assertEquals(name, s.lua("return vim.api.nvim_buf_get_name(" + buf + ")"));
            assertEquals("nofile", s.lua("return vim.bo[" + buf + "].buftype"));
            assertEquals("", s.lua("return vim.bo[" + buf + "].filetype"));
            assertEquals(Boolean.TRUE, s.lua("return vim.bo[" + buf + "].buflisted"));
            // Only the document is listed: the empty start-up buffer is gone.
            assertEquals(1, ((Number) s.lua("return #vim.fn.getbufinfo({ buflisted = 1 })")).intValue());
        }
    }

    @Test
    void closingADocumentWipesItsBufferAndAClosedAttachedBufferFallsBackToHome() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer a = new DocumentBuffer("");
            DocumentBuffer b = new DocumentBuffer("");
            s.showBuffer(a);
            s.showBuffer(b);
            int aBuf = a.bufnr();
            int bBuf = b.bufnr();

            s.closeBufferAsync(a);
            waitFor(() -> !a.isOpenIn(s), "buffer a to close");
            waitFor(() -> {
                try {
                    return Boolean.FALSE.equals(s.lua("return vim.api.nvim_buf_is_valid(" + aBuf + ")"));
                } catch (Exception e) {
                    return false;
                }
            }, "buffer a to be wiped");
            assertEquals(bBuf, s.getAttachedBufnr());

            s.closeBufferAsync(b);
            waitFor(() -> s.getAttachedBufnr() != bBuf, "the attached buffer to fall back");
            assertNotEquals(bBuf, currentBufnr(s));
            assertFalse(b.isOpenIn(s));
            assertThrows(Exception.class, () -> s.command("buffer " + bBuf));
        }
    }

    @Test
    void listingCommandOutputIsCapturedAndErrorsAreReadable() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer a = new DocumentBuffer(System.getProperty("java.io.tmpdir") + "/eclipse-neovim-test/A.txt");
            s.showBuffer(a);
            List<String> outputs = new CopyOnWriteArrayList<>();
            s.captureOutputAsync("ls", outputs::add);
            s.captureOutputAsync("marks q", outputs::add);
            waitFor(() -> outputs.size() == 2, "both outputs");
            assertTrue(outputs.get(0).contains("A.txt") && outputs.get(0).contains(String.valueOf(a.bufnr())),
                    outputs.get(0));
            assertTrue(outputs.get(1).startsWith("E283:"), outputs.get(1)); // no marks matching "q"
        }
    }
}
