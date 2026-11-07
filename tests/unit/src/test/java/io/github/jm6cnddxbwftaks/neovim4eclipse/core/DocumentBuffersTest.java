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
            LuaScripts.RELEASE_BUFFER, "RELEASE_BUFFER",
            LuaScripts.CLOSE_DOCUMENT_BUFFER, "CLOSE_DOCUMENT_BUFFER",
            LuaScripts.RENAME_DOCUMENT_BUFFER, "RENAME_DOCUMENT_BUFFER");

    /** The calls made, in order: {@code "DOCUMENT_BUFFER [5]"}, {@code "nvim_buf_delete ..."}. */
    private final List<String> calls = new CopyOnWriteArrayList<>();
    /** The answer to each call, by its name; an Exception fails it. Unlisted calls answer null. */
    private final Map<String, Object> answers = new HashMap<>();

    private final DocumentBuffers buffers = new DocumentBuffers(new MsgpackNeovimApi((method, params) -> {
        String name = method;
        List<Object> args = new ArrayList<>(List.of(params));
        if (method.equals("nvim_exec_lua")) {
            name = SCRIPT_NAMES.getOrDefault((String) params[0], "some other script");
            args = new ArrayList<>((List<?>) params[1]);
        }
        calls.add(name + " " + args);
        Object answer = answers.get(name);
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

