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

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
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
        long deadline = System.currentTimeMillis() + TestWait.millis();
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
            NeovimBarrier.pass(s); // a wrongly forwarded event would have arrived by now
            assertTrue(changes.stream().noneMatch(c -> c.newLines().contains("changed elsewhere")), changes.toString());
        }
    }

    /**
     * A change Neovim makes to a document's buffer clears its "in sync" stamp
     * (DocumentBuffer.syncedStamp), so the next attach pushes the document again; Eclipse's own write
     * does not.
     */
    @Test
    void aChangeNeovimMakesToABufferClearsItsInSyncStamp() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer a = new DocumentBuffer("");
            DocumentBuffer b = new DocumentBuffer("");
            s.showBuffer(a);
            s.setBufferLines(List.of("a"));
            s.showBuffer(b);
            s.setDocumentBufferLines(a, List.of("a", "by Eclipse"));
            a.markInSyncAt(7);
            NeovimBarrier.pass(s);
            assertTrue(a.isInSyncAt(7), "the echo of Eclipse's own write");

            s.lua("vim.api.nvim_buf_set_lines(" + a.bufnr() + ", 0, -1, false, {'by Neovim'})");
            waitFor(() -> !a.isInSyncAt(7), "the change to clear it");
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
            NeovimBarrier.pass(s); // their BufEnter notifications have been handled
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
     * A save Eclipse could not do fails the write the way a real failed write does
     * ({@code E212: Can't open file for writing}): the command reports the error to whoever ran it,
     * so {@code :w | q} does not go on and {@code :bufdo update} stops there.
     */
    @Test
    void aFailedSaveFailsTheWriteAndTheBufferStaysModified() throws Exception {
        try (NeovimSession s = start()) {
            s.setWriteHandler((document, request) -> DocumentWrite.Result.failed("Eclipse could not save A.txt: read-only"));
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

    /**
     * A typed {@code :w} is Neovim's write (B2b): its write hook asks Eclipse to save, passing
     * {@code ++p}, and shows the message Eclipse answers, as Vim shows {@code "A.txt" 1L, 2B written}.
     * {@code :w !cmd} pipes the text instead and asks Eclipse nothing.
     */
    @Test
    void aTypedWriteAsksEclipseAndShowsTheWrittenMessage() throws Exception {
        try (NeovimSession s = start()) {
            List<String> asked = new CopyOnWriteArrayList<>();
            s.setWriteHandler((document, request) -> {
                asked.add(document.name() + " " + request.kind() + (request.createFolders() ? " ++p" : "")
                        + (request.target().isEmpty() ? "" : " " + request.target()));
                return DocumentWrite.Result.saved("\"A.txt\" 1L, 2B written");
            });
            List<String> messages = new CopyOnWriteArrayList<>();
            s.addStatusListener(info -> messages.add(info.message()));
            Path dir = Files.createTempDirectory("neovim4eclipse-write");
            DocumentBuffer a = new DocumentBuffer(dir.resolve("A.txt").toString());
            s.showBuffer(a);
            s.setBufferLines(List.of("a"));

            s.input(":w ++p<CR>");
            waitFor(() -> messages.contains("\"A.txt\" 1L, 2B written"), "the written message: " + messages);
            assertEquals(List.of(a.name() + " OWN ++p"), asked);

            // A copy names its file in full: Neovim resolves the name typed in its current folder.
            Path copy = dir.resolve("copy.txt");
            s.input(":w " + copy + "<CR>");
            waitFor(() -> asked.size() == 2, "the copy: " + asked);
            assertEquals(a.name() + " COPY " + copy, asked.get(1));

            s.input(":w !cat >/dev/null<CR>");
            s.lua("return 0"); // the keys before it are done
            assertEquals(2, asked.size(), ":w !cmd is no write of the file: " + asked);
        }
    }

    /**
     * Closing a document keeps its buffer as Vim's {@code :bd} does: unloaded and unlisted, with its
     * number (docs/design/BUFFER_NUMBERS.md). A closed attached buffer falls back to the home buffer.
     */
    @Test
    void closingADocumentUnloadsItsBufferAndAClosedAttachedBufferFallsBackToHome() throws Exception {
        try (NeovimSession s = start()) {
            DocumentBuffer a = new DocumentBuffer("");
            DocumentBuffer b = new DocumentBuffer("");
            s.showBuffer(a);
            s.showBuffer(b);
            int aBuf = a.bufnr();
            int bBuf = b.bufnr();

            s.closeBufferAsync(a);
            waitFor(() -> !a.isOpenIn(s), "buffer a to close");
            waitFor(() -> unloadedButKept(s, aBuf), "buffer a to be unloaded, and kept");
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
            // And for the unload, as for a above: it comes a moment after Neovim leaves b.
            waitFor(() -> unloadedButKept(s, bBuf), "buffer b to be unloaded, and kept");
            assertFalse(b.isOpenIn(s));
        }
    }

    /** A file's next document gets the buffer of its closed one: the same number, as after Vim's :bd. */
    @Test
    void aReopenedFileGetsItsBufferNumberBack() throws Exception {
        try (NeovimSession s = start()) {
            String name = System.getProperty("java.io.tmpdir") + "/neovim4eclipse-test/Reopened.txt";
            DocumentBuffer first = new DocumentBuffer(name);
            DocumentBuffer other = new DocumentBuffer(System.getProperty("java.io.tmpdir") + "/neovim4eclipse-test/Other.txt");
            s.showBuffer(first);
            s.showBuffer(other);
            int number = first.bufnr();
            s.closeBufferAsync(first);
            waitFor(() -> unloadedButKept(s, number), "the closed buffer to be unloaded");

            DocumentBuffer again = new DocumentBuffer(name);
            s.showBuffer(again);
            assertEquals(number, again.bufnr(), "the buffer :bd kept, not a new one");
        }
    }

    /** Whether buffer {@code bufnr} exists, unloaded and unlisted, as Vim's :bd leaves it. */
    private static boolean unloadedButKept(NeovimSession s, int bufnr) {
        try {
            return Boolean.TRUE.equals(s.lua("return vim.api.nvim_buf_is_valid(" + bufnr + ")"
                    + " and not vim.api.nvim_buf_is_loaded(" + bufnr + ") and not vim.bo[" + bufnr + "].buflisted"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * A listing shows the document buffers (one line: on the message line, as on Neovim's own screen);
     * a listing's error is an error on the message line.
     */
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
            assertTrue(lines.stream().anyMatch(i -> !i.error() && i.message().contains("A.txt")
                    && i.message().startsWith(String.valueOf(a.bufnr()))), "the one-line :ls: " + lines);
            assertEquals(List.of(), outputs, "no popup for one line");
        }
    }
}
