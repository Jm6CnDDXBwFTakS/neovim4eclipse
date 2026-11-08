// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.logging.Level;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Handle;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.MsgpackNeovimApi;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog;

/**
 * {@link DocumentBuffers} when a step in Neovim fails: what it takes back and what it still tries.
 * Without Neovim: a scripted {@link MsgpackNeovimApi} records each call by name (an
 * {@code nvim_exec_lua} by the {@link LuaScripts} constant it runs) and answers from a table the
 * test fills; a call whose answer is an {@link Exception} fails with it. (The scripts themselves
 * are tested against a real Neovim: {@code NeovimBufferIntegrationTest}, the Lua specs.)
 */
class DocumentBuffersTest {

    private static final Map<String, String> SCRIPT_NAMES = Map.of(
            LuaScripts.CREATE_DOCUMENT_BUFFER, "CREATE_DOCUMENT_BUFFER",
            LuaScripts.DOCUMENT_BUFFER, "DOCUMENT_BUFFER",
            LuaScripts.SHOW_WINDOW, "SHOW_WINDOW",
            LuaScripts.OPEN_WINDOW, "OPEN_WINDOW",
            LuaScripts.HOME_BUFFER, "HOME_BUFFER",
            LuaScripts.RELEASE_BUFFER, "RELEASE_BUFFER",
            LuaScripts.CLOSE_WINDOW, "CLOSE_WINDOW",
            LuaScripts.CLOSE_DOCUMENT_BUFFER, "CLOSE_DOCUMENT_BUFFER",
            LuaScripts.RENAME_DOCUMENT_BUFFER, "RENAME_DOCUMENT_BUFFER");

    /** The calls made, in order: {@code "DOCUMENT_BUFFER [5]"}, {@code "nvim_buf_delete ..."}. */
    private final List<String> calls = new CopyOnWriteArrayList<>();
    /**
     * The answer to each call, by its name; an Exception fails it, a {@link Function} gets the call's
     * arguments and gives the answer. Unlisted calls answer null.
     */
    private final Map<String, Object> answers = new HashMap<>();

    @SuppressWarnings("unchecked") // the Function answers take the call's argument list
    private final DocumentBuffers buffers = new DocumentBuffers(new MsgpackNeovimApi((method, params) -> {
        String name = method;
        List<Object> args = new ArrayList<>(List.of(params));
        if (method.equals("nvim_exec_lua")) {
            name = SCRIPT_NAMES.getOrDefault((String) params[0], "some other script");
            args = new ArrayList<>((List<?>) params[1]);
        }
        calls.add(name + " " + args);
        Object answer = answers.get(name);
        if (answer instanceof Function<?, ?> byArgs) {
            answer = ((Function<List<Object>, ?>) byArgs).apply(args); // an answer that depends on the arguments
        }
        return answer instanceof Exception e ? CompletableFuture.failedFuture(e)
                : CompletableFuture.completedFuture(answer);
    }), Handle.buffer(1), 1000);

    DocumentBuffersTest() {
        answers.put("SHOW_WINDOW", 1000L);
        answers.put("nvim_buf_attach", true);
    }

    private boolean called(String name) {
        return calls.stream().anyMatch(call -> call.startsWith(name + " "));
    }

    // ---- a buffer whose setup fails ----

    /**
     * A named buffer whose setup fails is closed as a closed editor's, with the file's name kept,
     * so that the next show reuses it rather than making a second, unnamed one.
     */
    @Test
    void aNamedBufferWhoseSetupFailsIsClosedAndReusedByTheNextShow() throws Exception {
        DocumentBuffer doc = new DocumentBuffer("/ws/a.txt");
        answers.put("CREATE_DOCUMENT_BUFFER", List.of(5L, true, false));
        answers.put("DOCUMENT_BUFFER", new IllegalStateException("timed out"));

        assertThrows(Exception.class, () -> buffers.show(doc));

        assertTrue(calls.contains("CLOSE_DOCUMENT_BUFFER [5]"), calls.toString());
        assertFalse(buffers.owns(doc));
        assertNull(buffers.documentOf(5));

        answers.remove("DOCUMENT_BUFFER");
        answers.put("CREATE_DOCUMENT_BUFFER", List.of(5L, true, true)); // the kept buffer, by name
        buffers.show(doc);
        assertTrue(buffers.owns(doc));
        assertEquals(5, doc.bufnr);
        assertNotNull(buffers.documentOf(5));
    }

    /** An unnamed scratch buffer whose attach fails cannot be reused: it is wiped. */
    @Test
    void anUnnamedBufferWhoseAttachFailsIsWiped() {
        DocumentBuffer doc = new DocumentBuffer("");
        answers.put("nvim_create_buf", Handle.buffer(6));
        answers.put("nvim_buf_attach", new IllegalStateException("timed out"));

        assertThrows(Exception.class, () -> buffers.show(doc));

        assertTrue(calls.contains("nvim_buf_delete [" + Handle.buffer(6) + ", {force=true, unload=false}]"),
                calls.toString());
        assertFalse(buffers.owns(doc));
    }

    /** Neovim hangs: taking the buffer back fails too, which is logged with the buffer's number. */
    @Test
    void aBufferThatCannotBeTakenBackIsLogged() {
        DocumentBuffer doc = new DocumentBuffer("/ws/a.txt");
        answers.put("CREATE_DOCUMENT_BUFFER", List.of(5L, true, false));
        answers.put("DOCUMENT_BUFFER", new IllegalStateException("timed out"));
        answers.put("CLOSE_DOCUMENT_BUFFER", new IllegalStateException("timed out again"));

        try (ExpectedLog log = ExpectedLog.of(DocumentBuffers.class)) {
            Exception e = assertThrows(Exception.class, () -> buffers.show(doc));
            assertEquals(1, e.getSuppressed().length);
            log.assertLogged(Level.WARNING, "Buffer 5 could not be set up nor taken back");
        }
    }

    // ---- closing a buffer ----

    private DocumentBuffer shown(String name, long bufnr) throws Exception {
        DocumentBuffer doc = new DocumentBuffer(name);
        answers.put("CREATE_DOCUMENT_BUFFER", List.of(bufnr, true, false));
        buffers.show(doc);
        calls.clear();
        return doc;
    }

    /**
     * Leaving the buffer fails (it times out), and the buffer is closed all the same: otherwise it
     * would stay listed and attached in Neovim, unknown to Java, which forgot it already.
     */
    @Test
    void aBufferIsClosedEvenWhenLeavingItFails() throws Exception {
        DocumentBuffer doc = shown("/ws/a.txt", 5);
        answers.put("RELEASE_BUFFER", new IllegalStateException("timed out"));

        try (ExpectedLog log = ExpectedLog.of(DocumentBuffers.class)) {
            Exception e = assertThrows(Exception.class, () -> buffers.close(doc));
            assertTrue(e.getMessage().contains("buffer 5 (/ws/a.txt)"), e.getMessage());
            log.assertLogged(Level.WARNING, "Closing buffer 5 (/ws/a.txt) failed");
        }

        assertTrue(calls.contains("CLOSE_DOCUMENT_BUFFER [5]"), calls.toString());
        assertFalse(buffers.owns(doc));
        assertEquals(1, buffers.attachedBufnr(), "the home buffer is attached");
    }

    /** Both steps fail: the first failure is the cause, the second is kept with it. */
    @Test
    void whenLeavingAndClosingFailBothFailuresAreKept() throws Exception {
        DocumentBuffer doc = shown("/ws/a.txt", 5);
        answers.put("RELEASE_BUFFER", new IllegalStateException("release timed out"));
        answers.put("CLOSE_DOCUMENT_BUFFER", new IllegalStateException("close timed out"));

        try (ExpectedLog log = ExpectedLog.of(DocumentBuffers.class)) {
            Exception e = assertThrows(Exception.class, () -> buffers.close(doc));
            assertTrue(e.getCause().getMessage().contains("release timed out"), e.getCause().toString());
            assertEquals(1, e.getCause().getSuppressed().length);
        }
    }

    /**
     * A plug-in's {@code :bwipeout 5 6} took the attached buffer (6) as well. Closing 5's editor then
     * comes back to the home buffer: going back to 6 failed with E86, and the close was logged as
     * failed. SHOW_WINDOW answers -2 for a buffer that is gone.
     */
    @Test
    void closingWhenTheAttachedBufferWasWipedInNeovimReturnsToTheHomeBuffer() throws Exception {
        DocumentBuffer a = shown("/ws/a.txt", 5);
        shown("/ws/b.txt", 6);
        answers.put("SHOW_WINDOW", (Function<List<Object>, Object>) args -> "6".equals(args.get(1)) ? -2L : 1000L);

        try (ExpectedLog log = ExpectedLog.of(DocumentBuffers.class)) {
            buffers.close(a);
            log.assertNothingAtOrAbove(Level.WARNING);
        }

        // These documents have no editor window: both are shown in the home window (-1).
        int back = calls.indexOf("SHOW_WINDOW [-1, 6]");
        assertTrue(back >= 0, calls.toString());
        assertEquals("SHOW_WINDOW [-1, 1]", calls.get(back + 1), "then the home buffer");
        assertEquals(1, buffers.attachedBufnr(), "the home buffer is attached");
        assertTrue(calls.contains("CLOSE_DOCUMENT_BUFFER [5]"), calls.toString());
    }

    /**
     * The home buffer was wiped as well ({@code :bwipeout 1 6}): Neovim makes a new one (9), which
     * is attached for line events, shown in the home window and attached from then on.
     */
    @Test
    void closingWhenTheHomeBufferWasWipedTooShowsANewHomeBuffer() throws Exception {
        DocumentBuffer a = shown("/ws/a.txt", 5);
        shown("/ws/b.txt", 6);
        answers.put("SHOW_WINDOW", (Function<List<Object>, Object>) args -> switch ((String) args.get(1)) {
            case "1", "6" -> -2L; // wiped
            default -> 1000L;
        });
        answers.put("HOME_BUFFER", 9L);

        try (ExpectedLog log = ExpectedLog.of(DocumentBuffers.class)) {
            buffers.close(a);
            log.assertNothingAtOrAbove(Level.WARNING);
        }

        assertEquals(9, buffers.attachedBufnr(), "the new home buffer is attached");
        assertEquals(1000, buffers.attachedWin(), "in the home window");
        assertTrue(calls.contains("nvim_buf_attach [" + Handle.buffer(9) + ", true, {}]"), calls.toString());
        assertTrue(calls.contains("SHOW_WINDOW [-1, 9]"), calls.toString());
    }

    /** The new home buffer is wiped at once as well: nothing is shown, and the window id is never -2. */
    @Test
    void aNewHomeBufferWipedAtOnceLeavesTheHomeWindowAttached() throws Exception {
        DocumentBuffer a = shown("/ws/a.txt", 5);
        shown("/ws/b.txt", 6);
        answers.put("SHOW_WINDOW", (Function<List<Object>, Object>) args -> "5".equals(args.get(1)) ? 1000L : -2L);
        answers.put("HOME_BUFFER", 9L);

        try (ExpectedLog log = ExpectedLog.of(DocumentBuffers.class)) {
            buffers.close(a);
            log.assertNothingAtOrAbove(Level.WARNING);
        }

        assertEquals(9, buffers.attachedBufnr());
        assertEquals(1000, buffers.attachedWin(), "the home window, not -2");
    }

    /**
     * A buffer wiped in Neovim with no notice to Eclipse ({@code :noautocmd bwipeout 5}): the
     * editor's next show makes the buffer anew rather than failing on every activation.
     */
    @Test
    void showingADocumentWhoseBufferWasWipedMakesItAnew() throws Exception {
        DocumentBuffer doc = shown("/ws/a.txt", 5);
        shown("/ws/b.txt", 6);
        answers.put("SHOW_WINDOW", (Function<List<Object>, Object>) args -> "5".equals(args.get(1)) ? -2L : 1000L);
        answers.put("CREATE_DOCUMENT_BUFFER", List.of(7L, true, false));

        buffers.show(doc);

        assertEquals(7, doc.bufnr);
        assertEquals(7, buffers.attachedBufnr());
        assertNull(buffers.documentOf(5), "the wiped number is no document's");
        assertEquals(doc, buffers.documentOf(7));
        assertTrue(calls.contains("SHOW_WINDOW [-1, 7]"), calls.toString());
    }

    /**
     * The document's second editor is shown for the first time after the wipe: its window would be
     * opened on the wiped buffer, which OPEN_WINDOW refuses (-2). The buffer is made anew and the
     * window opened on that.
     */
    @Test
    void anEditorWindowOpenedOnAWipedBufferGetsTheBufferMadeAnew() throws Exception {
        DocumentBuffer doc = shown("/ws/a.txt", 5);
        answers.put("OPEN_WINDOW", (Function<List<Object>, Object>) args -> "5".equals(args.get(0)) ? -2L : 1001L);
        // As Neovim does: the window shown is the one asked for (-1: the home window, 1000).
        answers.put("SHOW_WINDOW", (Function<List<Object>, Object>) args -> "-1".equals(args.get(0)) ? 1000L
                : Long.parseLong((String) args.get(0)));
        answers.put("CREATE_DOCUMENT_BUFFER", List.of(7L, true, false));

        buffers.show(doc, new EditorWindow(), "/ws");

        assertEquals(7, doc.bufnr);
        assertEquals(7, buffers.attachedBufnr());
        assertTrue(calls.stream().anyMatch(c -> c.startsWith("OPEN_WINDOW [7, ")), calls.toString());
        assertTrue(calls.contains("SHOW_WINDOW [1001, 7]"), calls.toString());
        assertEquals(1001, buffers.attachedWin(), "the editor's new window, not the home window");
    }

    // ---- closing an editor's window ----

    /** A document shown in an editor's own window (1001), as Neovim answers: the window asked for. */
    private EditorWindow shownInWindow(String name, long bufnr) throws Exception {
        answers.put("OPEN_WINDOW", 1001L);
        answers.put("SHOW_WINDOW", (Function<List<Object>, Object>) args -> "-1".equals(args.get(0)) ? 1000L
                : Long.parseLong((String) args.get(0)));
        answers.put("CREATE_DOCUMENT_BUFFER", List.of(bufnr, true, false));
        EditorWindow window = new EditorWindow();
        buffers.show(new DocumentBuffer(name), window, "/ws");
        calls.clear();
        return window;
    }

    /**
     * The home buffer was wiped in Neovim ({@code :bwipeout 2}), then the active editor closes:
     * Neovim makes a new home buffer (9) for the home window and names it in CLOSE_WINDOW's answer,
     * and Eclipse attaches it, in the same one call, rather than the gone one.
     */
    @Test
    void closingTheActiveWindowAfterTheHomeBufferWasWipedAttachesANewOne() throws Exception {
        EditorWindow window = shownInWindow("/ws/a.txt", 5);
        answers.put("CLOSE_WINDOW", List.of(1000L, 9L));

        buffers.closeWindow(window);

        assertEquals(9, buffers.attachedBufnr(), "the new home buffer is attached");
        assertNull(buffers.attachedDocument());
        assertEquals(1000, buffers.attachedWin(), "in the home window");
        assertEquals(List.of("CLOSE_WINDOW [1001, 1]", "nvim_buf_attach [" + Handle.buffer(9) + ", true, {}]"),
                calls, "no call of its own to ask for the home buffer");
    }

    /** The home buffer is still there: it is not attached a second time. */
    @Test
    void closingTheActiveWindowKeepsAHomeBufferThatIsStillThere() throws Exception {
        EditorWindow window = shownInWindow("/ws/a.txt", 5);
        answers.put("CLOSE_WINDOW", List.of(1000L, 1L));

        buffers.closeWindow(window);

        assertEquals(1, buffers.attachedBufnr());
        assertEquals(List.of("CLOSE_WINDOW [1001, 1]"), calls);
    }

    /** Closing a window that is not the attached one leaves the attached document as it is. */
    @Test
    void closingAnotherWindowKeepsTheAttachedDocument() throws Exception {
        EditorWindow other = shownInWindow("/ws/b.txt", 6);
        answers.put("OPEN_WINDOW", 1002L);
        answers.put("CREATE_DOCUMENT_BUFFER", List.of(5L, true, false));
        DocumentBuffer a = new DocumentBuffer("/ws/a.txt");
        buffers.show(a, new EditorWindow(), "/ws");
        calls.clear();
        answers.put("CLOSE_WINDOW", List.of(1000L, 1L));

        buffers.closeWindow(other);

        assertEquals(5, buffers.attachedBufnr());
        assertEquals(a, buffers.attachedDocument());
        assertEquals(1002, buffers.attachedWin());
        assertEquals(List.of("CLOSE_WINDOW [1001, 5]"), calls);
    }

    /** Neovim does not answer the close: the closed editor's document is not left attached. */
    @Test
    void aFailedCloseOfTheActiveWindowLeavesTheHomeBufferAttached() throws Exception {
        EditorWindow window = shownInWindow("/ws/a.txt", 5);
        answers.put("CLOSE_WINDOW", new IllegalStateException("timed out"));

        assertThrows(Exception.class, () -> buffers.closeWindow(window));

        assertEquals(1, buffers.attachedBufnr());
        assertNull(buffers.attachedDocument());
        assertEquals(1000, buffers.attachedWin());
    }

    /**
     * The home buffer is wiped while it is the attached one (no editor active): Neovim's BufEnter of
     * the buffer it took instead brings Eclipse back (returnToAttached), which finds the home buffer
     * gone and shows a new one.
     */
    @Test
    void returningToAWipedAttachedHomeBufferShowsANewOne() throws Exception {
        answers.put("SHOW_WINDOW", (Function<List<Object>, Object>) args -> "1".equals(args.get(1)) ? -2L : 1000L);
        answers.put("HOME_BUFFER", 9L);

        buffers.returnToAttached();

        assertEquals(9, buffers.attachedBufnr());
        assertEquals(1000, buffers.attachedWin());
        assertTrue(calls.contains("SHOW_WINDOW [-1, 9]"), calls.toString());
    }

    /**
     * The last editor of a document shown in the home window (its own window was closed in Neovim)
     * closes after the home buffer was wiped: RELEASE_BUFFER puts a new home buffer there itself and
     * names it, and Eclipse takes it and goes back to it straight away; the close logs nothing.
     */
    @Test
    void closingADocumentAfterTheHomeBufferWasWipedTakesTheNewOne() throws Exception {
        DocumentBuffer a = shown("/ws/a.txt", 5);
        answers.put("RELEASE_BUFFER", List.of(List.of(), 9L));
        answers.put("SHOW_WINDOW", (Function<List<Object>, Object>) args -> "1".equals(args.get(1)) ? -2L : 1000L);

        try (ExpectedLog log = ExpectedLog.of(DocumentBuffers.class)) {
            buffers.close(a);
            log.assertNothingAtOrAbove(Level.WARNING);
        }

        assertEquals(List.of("RELEASE_BUFFER [5, 1]", "nvim_buf_attach [" + Handle.buffer(9) + ", true, {}]",
                "SHOW_WINDOW [-1, 9]", "CLOSE_DOCUMENT_BUFFER [5]"), calls, "no call to ask for the home buffer");
        assertEquals(9, buffers.attachedBufnr());
    }

    /**
     * Making the wiped buffer anew fails (Neovim does not answer), then the editor closes: the
     * closed document does not stay attached, the home buffer is.
     */
    @Test
    void closingAfterTheMakingAnewFailedAttachesTheHomeBuffer() throws Exception {
        DocumentBuffer doc = shown("/ws/a.txt", 5);
        answers.put("SHOW_WINDOW", (Function<List<Object>, Object>) args -> "5".equals(args.get(1)) ? -2L : 1000L);
        answers.put("CREATE_DOCUMENT_BUFFER", new IllegalStateException("timed out"));
        assertThrows(Exception.class, () -> buffers.show(doc));
        calls.clear();

        buffers.close(doc);

        assertEquals(1, buffers.attachedBufnr(), "the home buffer is attached");
        assertNull(buffers.attachedDocument(), "no closed document");
        assertTrue(calls.contains("SHOW_WINDOW [-1, 1]"), "and Neovim shows it: " + calls);
    }

    /** The switch to the home buffer in that close fails too: logged at WARNING, saying what failed. */
    @Test
    void aFailedSwitchToTheHomeBufferAfterAFailedMakingAnewIsLogged() throws Exception {
        DocumentBuffer doc = shown("/ws/a.txt", 5);
        answers.put("SHOW_WINDOW", (Function<List<Object>, Object>) args -> "5".equals(args.get(1)) ? -2L : 1000L);
        answers.put("CREATE_DOCUMENT_BUFFER", new IllegalStateException("timed out"));
        assertThrows(Exception.class, () -> buffers.show(doc));
        answers.put("SHOW_WINDOW", new IllegalStateException("timed out again"));

        try (ExpectedLog log = ExpectedLog.of(DocumentBuffers.class)) {
            assertThrows(IllegalStateException.class, () -> buffers.close(doc));
            log.assertLogged(Level.WARNING, "Showing the home buffer after closing the editor of buffer 5 (/ws/a.txt)");
        }
        assertEquals(1, buffers.attachedBufnr(), "the home buffer is attached all the same");
    }

    /** Wiped again as soon as it was made: the show fails, naming the buffer. */
    @Test
    void aBufferWipedAgainAsSoonAsItWasMadeFailsTheShow() throws Exception {
        DocumentBuffer doc = shown("/ws/a.txt", 5);
        answers.put("SHOW_WINDOW", -2L);
        answers.put("CREATE_DOCUMENT_BUFFER", List.of(7L, true, false));

        Exception e = assertThrows(IllegalStateException.class, () -> buffers.show(doc));
        assertTrue(e.getMessage().contains("wiped in Neovim"), e.getMessage());
    }

    /** Eclipse no longer knows the window: closing it again asks nothing of Neovim. */
    private void assertForgotten(EditorWindow window) throws Exception {
        calls.clear();
        buffers.closeWindow(window);
        assertEquals(List.of(), calls, "the window is forgotten");
    }

    /**
     * The document's editor windows close with its buffer (RELEASE_BUFFER names them before the
     * home buffer): Eclipse forgets them, and the attached one hands over to the home window.
     */
    @Test
    void theWindowsReleasedWithABufferAreForgotten() throws Exception {
        EditorWindow window = shownInWindow("/ws/a.txt", 5);
        DocumentBuffer a = buffers.documentAt(5);
        answers.put("RELEASE_BUFFER", List.of(List.of(1001L), 1L));

        buffers.close(a);

        assertForgotten(window);
        assertEquals(1000, buffers.attachedWin(), "the home window");
        assertEquals(1, buffers.attachedBufnr());
    }

    /** Taking the new home buffer fails: the windows released are forgotten all the same. */
    @Test
    void theReleasedWindowsAreForgottenWhenTakingTheNewHomeBufferFails() throws Exception {
        EditorWindow window = shownInWindow("/ws/a.txt", 5);
        DocumentBuffer a = buffers.documentAt(5);
        answers.put("RELEASE_BUFFER", List.of(List.of(1001L), 9L));
        answers.put("nvim_buf_attach", new IllegalStateException("timed out"));

        try (ExpectedLog log = ExpectedLog.of(DocumentBuffers.class)) {
            assertThrows(Exception.class, () -> buffers.close(a));
        }

        assertForgotten(window);
        assertEquals(1000, buffers.attachedWin(), "the home window, not the closed one");
    }

    @Test
    void aBufferThatClosesWellLeavesItThenClosesIt() throws Exception {
        DocumentBuffer doc = shown("/ws/a.txt", 5);

        buffers.close(doc);

        assertEquals("RELEASE_BUFFER [5, 1]", calls.get(0));
        assertEquals("CLOSE_DOCUMENT_BUFFER [5]", calls.get(calls.size() - 1));
        assertFalse(called("nvim_buf_delete"));
    }

    // ---- a buffer renamed after its file ----

    /** A buffer this Neovim made is renamed in place: it keeps its number (and Neovim its undo history). */
    @Test
    void aShownBufferIsRenamedInNeovimAndKeepsItsNumber() throws Exception {
        DocumentBuffer doc = new DocumentBuffer("/ws/a.txt");
        answers.put("CREATE_DOCUMENT_BUFFER", List.of(5L, true, false));
        buffers.show(doc);
        answers.put("RENAME_DOCUMENT_BUFFER", true);

        doc.rename("/ws/b.txt");
        buffers.rename(doc);

        assertTrue(calls.contains("RENAME_DOCUMENT_BUFFER [5, /ws/b.txt]"), calls.toString());
        assertTrue(buffers.owns(doc));
        assertEquals(5, doc.bufnr);
    }

    /** Neovim refuses the name (a loaded buffer has it): the buffer is closed, to be made anew under it. */
    @Test
    void aRefusedRenameClosesTheBuffer() throws Exception {
        DocumentBuffer doc = new DocumentBuffer("/ws/a.txt");
        answers.put("CREATE_DOCUMENT_BUFFER", List.of(5L, true, false));
        buffers.show(doc);
        answers.put("RENAME_DOCUMENT_BUFFER", false);

        doc.rename("/ws/b.txt");
        try (ExpectedLog log = ExpectedLog.of(DocumentBuffers.class)) {
            buffers.rename(doc);
            log.assertLogged(Level.WARNING, "Neovim refused the name /ws/b.txt for buffer 5");
        }

        assertTrue(calls.contains("CLOSE_DOCUMENT_BUFFER [5]"), calls.toString());
        assertFalse(buffers.owns(doc));
    }

    /**
     * The attached buffer's rename refused: the close leaves the home buffer attached for a moment,
     * and the editor's attach that follows the rename (queued right behind it) makes and attaches a
     * buffer under the new name. That show runs before any key typed after the rename.
     */
    @Test
    void aRefusedRenameOfTheAttachedBufferIsShownAgainByTheNextShow() throws Exception {
        DocumentBuffer doc = new DocumentBuffer("/ws/a.txt");
        answers.put("CREATE_DOCUMENT_BUFFER", List.of(5L, true, false));
        buffers.show(doc);
        answers.put("RENAME_DOCUMENT_BUFFER", false);
        doc.rename("/ws/b.txt");
        try (ExpectedLog log = ExpectedLog.of(DocumentBuffers.class)) {
            buffers.rename(doc);
        }
        assertEquals(1, buffers.attachedBufnr(), "the home buffer until the attach");
        assertNull(buffers.attachedDocument());

        answers.put("CREATE_DOCUMENT_BUFFER", List.of(7L, true, false));
        buffers.show(doc); // the attach's
        assertTrue(calls.stream().anyMatch(call -> call.startsWith("CREATE_DOCUMENT_BUFFER [/ws/b.txt")), calls.toString());
        assertEquals(7, buffers.attachedBufnr());
        assertEquals(doc, buffers.attachedDocument());
        assertFalse(doc.isInSyncAt(1), "out of sync: the attach pushes the document into it");
    }

    /**
     * A buffer this Neovim has not made (not shown yet, or a Neovim restarted since) is not renamed
     * in Neovim; it is made under the new name when shown.
     */
    @Test
    void aBufferNotMadeYetIsMadeUnderItsNewName() throws Exception {
        DocumentBuffer doc = new DocumentBuffer("/ws/a.txt");
        doc.rename("/ws/b.txt");
        buffers.rename(doc);
        assertFalse(called("RENAME_DOCUMENT_BUFFER"), calls.toString());

        answers.put("CREATE_DOCUMENT_BUFFER", List.of(5L, true, false));
        buffers.show(doc);
        assertTrue(calls.stream().anyMatch(call -> call.startsWith("CREATE_DOCUMENT_BUFFER [/ws/b.txt")), calls.toString());
    }
}

