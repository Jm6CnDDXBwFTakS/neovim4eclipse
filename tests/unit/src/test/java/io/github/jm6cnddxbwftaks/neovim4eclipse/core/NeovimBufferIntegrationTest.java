// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/** One Neovim buffer per Eclipse document, against a real {@code nvim} (skipped when none is found). */
@Tag("integration")
class NeovimBufferIntegrationTest {

    private static final String NVIM = TestNeovim.PATH;

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

    /** {@code :bufdo} edits buffers that are not attached; each change goes to that document's listener. */
    @Test
    void changesToADocumentThatIsNotAttachedGoToItsOwnListener() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer a = new DocumentBuffer("");
            DocumentBuffer b = new DocumentBuffer("");
            s.showBuffer(a);
            s.setBufferLines(List.of("a old"));
            s.showBuffer(b);
            s.setBufferLines(List.of("b old"));
            List<BufferChange> ofA = new CopyOnWriteArrayList<>();
            List<BufferChange> ofB = new CopyOnWriteArrayList<>();
            List<BufferChange> attached = new CopyOnWriteArrayList<>();
            s.addUnattachedChangeListener(a, ofA::add);
            s.addUnattachedChangeListener(b, ofB::add);
            s.addBufferChangeListener(attached::add);

            s.command("bufdo s/old/new/");

            waitFor(() -> !ofA.isEmpty() && !attached.isEmpty(), "both changes");
            assertEquals(new BufferChange(0, 1, List.of("a new")), ofA.get(0));
            assertTrue(attached.stream().anyMatch(c -> c.newLines().contains("b new")), attached.toString());
            assertEquals(List.of(), ofB, "b is attached: its change goes to the attached editor");
        }
    }

    /**
     * Eclipse's change to a document in the background reaches its buffer, without coming back as a
     * line event; a buffer that was never attached (so does not exist yet) is left alone.
     */
    @Test
    void eclipseWritesADocumentBufferThatIsNotAttachedWithoutAnEcho() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer a = new DocumentBuffer("");
            DocumentBuffer b = new DocumentBuffer("");
            s.showBuffer(a);
            s.setBufferLines(List.of("one", "two"));
            s.showBuffer(b);
            s.setBufferLines(List.of("b"));
            List<BufferChange> ofA = new CopyOnWriteArrayList<>();
            s.addUnattachedChangeListener(a, ofA::add);

            assertTrue(s.setDocumentBufferLines(a, List.of("one", "TWO")));

            assertEquals(List.of("one", "TWO"), s.lua("return vim.api.nvim_buf_get_lines(" + a.bufnr() + ", 0, -1, false)"));
            assertEquals(List.of("b"), s.getBufferLines(), "the attached buffer is untouched");
            s.lua("vim.api.nvim_buf_set_lines(" + a.bufnr() + ", 0, 1, false, {'ONE'})"); // Neovim's own change...
            waitFor(() -> !ofA.isEmpty(), "Neovim's own change");
            assertEquals(List.of(new BufferChange(0, 1, List.of("ONE"))), ofA, "...is the only one reported");
            assertFalse(s.setDocumentBufferLines(new DocumentBuffer(""), List.of("x")), "never attached");
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

    /**
     * {@code :bufdo} enters every buffer. The handler hears of each, and {@code getEnteredBufnr}
     * names the last one, so the editor can skip the buffers Neovim has already left.
     */
    @Test
    void theLastBufferEnteredIsKnownWhenACommandWalksThroughSeveral() throws Exception {
        try (NeovimSession s = start()) {
            List<Integer> entered = new CopyOnWriteArrayList<>();
            s.setBufferEnterHandler(entered::add);
            DocumentBuffer a = new DocumentBuffer("");
            DocumentBuffer b = new DocumentBuffer("");
            DocumentBuffer c = new DocumentBuffer("");
            s.showBuffer(a); // created in this order, so numbered a < b < c
            s.showBuffer(b);
            s.showBuffer(c);
            s.showBuffer(b);
            assertEquals(b.bufnr(), s.getEnteredBufnr(), "Eclipse's own switch");

            s.command("bufdo let b:visited = 1"); // a, b, c in buffer-number order: ends in c
            waitFor(() -> entered.size() >= 2, "the walk");
            assertEquals(List.of(a.bufnr(), c.bufnr()), entered, "b is attached, so only a and c are reported");
            assertEquals(c.bufnr(), s.getEnteredBufnr());

            s.command("buffer " + a.bufnr());
            waitFor(() -> s.getEnteredBufnr() == a.bufnr(), "the jump to a");
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
            String name = System.getProperty("java.io.tmpdir") + "/neovim4eclipse-test/Example.java";
            DocumentBuffer a = new DocumentBuffer(name);
            s.showBuffer(a);
            int buf = a.bufnr();
            assertEquals(name, s.lua("return vim.api.nvim_buf_get_name(" + buf + ")"));
            assertEquals("acwrite", s.lua("return vim.bo[" + buf + "].buftype"), "written only through Eclipse");
            assertEquals("", s.lua("return vim.bo[" + buf + "].filetype"));
            assertEquals(Boolean.TRUE, s.lua("return vim.bo[" + buf + "].buflisted"));
            // Only the document is listed: the empty start-up buffer is gone.
            assertEquals(1, ((Number) s.lua("return #vim.fn.getbufinfo({ buflisted = 1 })")).intValue());
        }
    }

    /**
     * A write by Neovim itself ({@code :update}, {@code :bufdo update}, a mapping's {@code :w}) asks
     * Eclipse to save the document, and Neovim never writes the file. A freshly loaded document is
     * not modified, so {@code :update} skips it, as for a file just read.
     */
    @Test
    void neovimsWritesAskEclipseToSave() throws Exception {
        try (NeovimSession s = start()) {
            List<DocumentBuffer> saved = new CopyOnWriteArrayList<>();
            s.setWriteHandler(document -> {
                saved.add(document);
                return null;
            });
            Path dir = Files.createTempDirectory("neovim4eclipse-write");
            DocumentBuffer a = new DocumentBuffer(dir.resolve("A.txt").toString());
            DocumentBuffer b = new DocumentBuffer(dir.resolve("B.txt").toString());
            s.showBuffer(a);
            s.setBufferLines(List.of("a"));
            s.showBuffer(b);
            s.setBufferLines(List.of("b"));
            assertEquals(Boolean.FALSE, s.lua("return vim.bo.modified"), "just loaded");

            s.lua("vim.api.nvim_buf_set_lines(" + a.bufnr() + ", 0, -1, false, {'A'})");
            s.command("bufdo update");

            assertEquals(List.of(a), saved, "only the modified one");
            assertEquals(Boolean.FALSE, s.lua("return vim.bo[" + a.bufnr() + "].modified"), "saved");
            assertFalse(Files.exists(dir.resolve("A.txt")), "Neovim never writes the file itself");
        }
    }

    /**
     * A save Eclipse could not do fails the write the way a real failed write does
     * ({@code E212: Can't open file for writing}): the command reports the error to whoever ran it,
     * so {@code :w | q} does not go on and {@code :bufdo update} stops there.
     */
    @Test
    void aFailedSaveFailsTheWriteAndTheBufferStaysModified() throws Exception {
        try (NeovimSession s = start()) {
            s.setWriteHandler(document -> "Eclipse could not save A.txt: read-only");
            DocumentBuffer a = new DocumentBuffer(System.getProperty("java.io.tmpdir") + "/neovim4eclipse-test/A.txt");
            s.showBuffer(a);
            s.setBufferLines(List.of("a"));
            s.input("x");

            Exception failed = assertThrows(Exception.class, () -> s.command("write"));

            assertTrue(String.valueOf(failed.getMessage()).contains("Eclipse could not save A.txt: read-only"),
                    String.valueOf(failed.getMessage()));
            assertEquals(Boolean.TRUE, s.lua("return vim.bo.modified"), ":update tries again");

            // Typed (or from a mapping) rather than through the API: the error is a message, two
            // lines with Neovim's header for an error in an autocommand, so it opens the popup.
            // Through :execute, since a typed :write is handed to Eclipse (LuaScripts.HOST_COMMANDS).
            List<CommandOutput> outputs = new CopyOnWriteArrayList<>();
            s.addOutputListener(outputs::add);
            s.input(":execute 'write'<CR>");
            waitFor(() -> outputs.stream().anyMatch(o -> o.text().equals("Error in BufWriteCmd Autocommands for \"<buffer="
                    + a.bufnr() + ">\":\nEclipse could not save A.txt: read-only")), "the error in the popup: " + outputs);
        }
    }

    /**
     * The safety nets: a document buffer that Neovim deletes by itself ({@code <Cmd>bd},
     * {@code vim.cmd.bdelete()}), or a {@code :quit} it runs itself, tells Eclipse which buffer, so
     * that its editor closes. A {@code :bd} typed on the command line is handed over instead and
     * deletes nothing in Neovim.
     */
    @Test
    void aBufferNeovimDeletesOrQuitsByItselfIsReported() throws Exception {
        try (NeovimSession s = start()) {
            List<Integer> closed = new CopyOnWriteArrayList<>();
            s.setBufferClosedHandler(closed::add);
            String dir = System.getProperty("java.io.tmpdir") + "/neovim4eclipse-test/";
            DocumentBuffer a = new DocumentBuffer(dir + "A.txt");
            DocumentBuffer b = new DocumentBuffer(dir + "B.txt");
            s.showBuffer(a);
            s.showBuffer(b);

            s.lua("vim.cmd.bdelete(" + a.bufnr() + ")");
            waitFor(() -> closed.contains(a.bufnr()), "A's deletion: " + closed);

            s.command("split"); // two windows, so the quit closes one instead of ending Neovim
            closed.clear();
            s.lua("vim.cmd.quit()");
            waitFor(() -> closed.contains(b.bufnr()), "the quit of B's window: " + closed);
            assertTrue(s.isAlive());
        }
    }

    /**
     * Neovim's {@code 'modified'} follows Eclipse's dirty flag: set or cleared on request, and kept
     * through a push of Eclipse's text (writing lines would mark the buffer modified, though a
     * revert is not dirty). So {@code :update} writes exactly what Eclipse would save.
     */
    @Test
    void modifiedFollowsEclipsesDirtyFlag() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer a = new DocumentBuffer(System.getProperty("java.io.tmpdir") + "/neovim4eclipse-test/A.txt");
            s.showBuffer(a);
            s.setModifiedAsync(a, true); // an editor with unsaved changes, attached
            waitFor(() -> a.eclipseDirty, "the flag recorded");
            s.setBufferLines(List.of("a"));
            assertEquals(Boolean.TRUE, s.lua("return vim.bo.modified"), "the first load keeps it modified");

            s.setModifiedAsync(a, false); // Ctrl+S in Eclipse; queued on the input thread
            waitFor(() -> Boolean.FALSE.equals(lua(s, "return vim.bo.modified")), "'modified' cleared");

            s.setBufferLines(List.of("b")); // :e! in Eclipse: the saved text comes back, not dirty
            assertEquals(Boolean.FALSE, s.lua("return vim.bo.modified"), "the push keeps Eclipse's flag");
            s.setModifiedAsync(a, true);
            waitFor(() -> Boolean.TRUE.equals(lua(s, "return vim.bo.modified")), "'modified' set");
        }
    }

    private static Object lua(NeovimSession s, String code) {
        try {
            return s.lua(code);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code :bufdo update} stops at a buffer whose save failed, as it stops at a real write error. */
    @Test
    void bufdoUpdateStopsAtAFailedSave() throws Exception {
        try (NeovimSession s = start()) {
            String dir = System.getProperty("java.io.tmpdir") + "/neovim4eclipse-test/";
            DocumentBuffer a = new DocumentBuffer(dir + "A.txt");
            DocumentBuffer b = new DocumentBuffer(dir + "B.txt");
            List<DocumentBuffer> saved = new CopyOnWriteArrayList<>();
            s.setWriteHandler(document -> {
                saved.add(document);
                return document.equals(a) ? "Eclipse could not save A.txt: read-only" : null;
            });
            // Shown first, so A has the lower buffer number and :bufdo reaches it first.
            s.showBuffer(a);
            s.setBufferLines(List.of("a"));
            s.input("x");
            s.showBuffer(b);
            s.setBufferLines(List.of("b"));
            s.input("x");
            assertTrue(a.bufnr() < b.bufnr(), "buffer order");

            assertThrows(Exception.class, () -> s.command("bufdo update"));

            assertEquals(List.of(a), saved, "A is first in the buffer list; B is not tried after A failed");
            assertEquals(Boolean.TRUE, s.lua("return vim.bo[" + b.bufnr() + "].modified"));
        }
    }

    /** Eclipse saves a document only as its own file; {@code :w other.txt} is refused, nothing is written. */
    @Test
    void aWriteUnderAnotherNameIsRefused() throws Exception {
        try (NeovimSession s = start()) {
            List<DocumentBuffer> saved = new CopyOnWriteArrayList<>();
            s.setWriteHandler(document -> {
                saved.add(document);
                return null;
            });
            Path dir = Files.createTempDirectory("neovim4eclipse-write");
            DocumentBuffer a = new DocumentBuffer(dir.resolve("A.txt").toString());
            s.showBuffer(a);
            s.setBufferLines(List.of("a"));

            Exception refused = assertThrows(Exception.class, () -> s.command("write " + dir.resolve("other.txt")));

            assertEquals(List.of(), saved);
            assertTrue(String.valueOf(refused.getMessage()).contains("only under its own name"),
                    String.valueOf(refused.getMessage()));
            assertFalse(Files.exists(dir.resolve("other.txt")));
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
            // The session records the fallback before Neovim has switched: on a slow machine (the
            // macOS CI runner) Neovim's current buffer was still b right after. Wait for Neovim too.
            waitFor(() -> {
                try {
                    return currentBufnr(s) != bBuf;
                } catch (Exception e) {
                    return false;
                }
            }, "Neovim to leave buffer b");
            assertFalse(b.isOpenIn(s));
            assertThrows(Exception.class, () -> s.command("buffer " + bBuf));
        }
    }

    /** A listing shows the document buffers; a listing's error is an error on the message line. */
    @Test
    void listingsShowTheDocumentBuffersAndTheirErrors() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer a = new DocumentBuffer(System.getProperty("java.io.tmpdir") + "/neovim4eclipse-test/A.txt");
            s.showBuffer(a);
            List<CommandOutput> outputs = new CopyOnWriteArrayList<>();
            List<StatusInfo> lines = new CopyOnWriteArrayList<>();
            s.addOutputListener(outputs::add);
            s.addStatusListener(lines::add);
            s.commandAsync("ls");
            s.commandAsync("marks q");
            waitFor(() -> lines.stream().anyMatch(i -> i.error() && i.message().startsWith("E283:")),
                    "no marks matching \"q\": " + lines);
            assertTrue(outputs.get(0).text().contains("A.txt") && outputs.get(0).text().contains(String.valueOf(a.bufnr())),
                    outputs.toString());
        }
    }
}
