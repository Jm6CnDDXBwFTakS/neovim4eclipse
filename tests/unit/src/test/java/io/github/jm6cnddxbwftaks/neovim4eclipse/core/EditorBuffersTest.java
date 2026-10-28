// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

/**
 * Which editors share a document's buffer, which editor Eclipse activates when Neovim switches to a
 * buffer by itself, and when a buffer is given up. Documents and editors are plain strings here; in
 * the plug-in they are {@code IDocument} and {@code IEditorPart}.
 */
class EditorBuffersTest {

    private static final Predicate<DocumentBuffer> OPEN = b -> true;

    private final EditorBuffers<String, String> buffers = new EditorBuffers<>();

    /** Gives {@code buffer} the number Neovim would, as {@code DocumentBuffers.show} does. */
    private static DocumentBuffer numbered(DocumentBuffer buffer, int bufnr) {
        buffer.bufnr = bufnr;
        return buffer;
    }

    @Test
    void twoEditorsOnOneDocumentShareItsBuffer() {
        DocumentBuffer first = buffers.add("A.java", "editor 1", () -> "/src/A.java");
        DocumentBuffer second = buffers.add("A.java", "editor 2", () -> "/src/A.java");

        assertSame(first, second);
        assertEquals("/src/A.java", first.name);
    }

    @Test
    void theBufferOfADocumentIsFoundByTheDocument() {
        DocumentBuffer a = buffers.add("A.java", "editor 1", () -> "/src/A.java");
        assertSame(a, buffers.bufferOf("A.java").orElseThrow());
        assertTrue(buffers.bufferOf("B.java").isEmpty(), "no editor on B.java was attached");
    }

    @Test
    void eachDocumentHasItsOwnBufferAndTheNameIsAskedForOnlyOnce() {
        AtomicInteger asked = new AtomicInteger();
        DocumentBuffer a = buffers.add("A.java", "editor 1", () -> "A" + asked.incrementAndGet());
        buffers.add("A.java", "editor 2", () -> "A" + asked.incrementAndGet());
        DocumentBuffer b = buffers.add("B.java", "editor 3", () -> "B");

        assertNotSame(a, b);
        assertEquals(1, asked.get(), "the existing buffer is reused without making a name");
    }

    @Test
    void neovimsSwitchActivatesTheFirstEditorOpenedOnThatDocument() {
        numbered(buffers.add("A.java", "editor 1", () -> "A"), 3);
        // Many editors, so that hash order would very likely differ from opening order.
        for (int i = 2; i <= 20; i++) {
            buffers.add("A.java", "editor " + i, () -> "A");
        }
        buffers.add("A.java", "editor 1", () -> "A"); // re-activating keeps its place

        assertEquals(Optional.of(new EditorBuffers.Showing<>("A.java", "editor 1")), buffers.editorShowing(3, OPEN));

        buffers.remove("editor 1");
        assertEquals("editor 2", buffers.editorShowing(3, OPEN).orElseThrow().editor(),
                "with the first one closed, the next one opened");
    }

    @Test
    void neovimsSwitchFindsTheDocumentByBufferNumber() {
        numbered(buffers.add("A.java", "editor 1", () -> "A"), 3);
        numbered(buffers.add("B.java", "editor 2", () -> "B"), 4);

        assertEquals(new EditorBuffers.Showing<>("B.java", "editor 2"), buffers.editorShowing(4, OPEN).orElseThrow());
        assertTrue(buffers.editorShowing(5, OPEN).isEmpty(), "help, :e of another file: no editor, Neovim is sent back");
    }

    @Test
    void aBufferFromARestartedNeovimIsNotFollowed() {
        DocumentBuffer stale = numbered(buffers.add("A.java", "editor 1", () -> "A"), 3);

        assertTrue(buffers.editorShowing(3, b -> b != stale).isEmpty(),
                "number 3 may now be another buffer in the new Neovim");
    }

    @Test
    void aBufferIsGivenUpWhenItsLastEditorCloses() {
        DocumentBuffer a = numbered(buffers.add("A.java", "editor 1", () -> "A"), 3);
        buffers.add("A.java", "editor 2", () -> "A");

        assertEquals(List.of(), buffers.remove("editor 1"), "editor 2 still shows A.java");
        assertEquals(List.of(a), buffers.remove("editor 2"));
        assertTrue(buffers.editorShowing(3, OPEN).isEmpty(), "a closed document is not followed");
        assertNotSame(a, buffers.add("A.java", "editor 3", () -> "A"), "reopening makes a fresh buffer");
    }

    @Test
    void removingAnEditorThatShowsNothingChangesNothing() {
        DocumentBuffer a = buffers.add("A.java", "editor 1", () -> "A");

        assertEquals(List.of(), buffers.remove("editor 9"));
        assertSame(a, buffers.add("A.java", "editor 2", () -> "A"));
    }

    @Test
    void anEditorReusedForAnotherFileGivesUpBothBuffersWhenItCloses() {
        // Eclipse's "reuse editors" setting (e.g. for search results) shows a new file in the same editor.
        DocumentBuffer a = buffers.add("A.java", "search editor", () -> "A");
        DocumentBuffer b = buffers.add("B.java", "search editor", () -> "B");
        buffers.add("C.java", "editor 2", () -> "C");

        assertEquals(List.of(a, b), buffers.remove("search editor"));
    }

    @Test
    void aDocumentIsSharedOnlyWhenAnotherEditorShowsIt() {
        buffers.add("A.java", "editor 1", () -> "A");
        assertFalse(buffers.isSharedWithAnotherEditor("A.java", "editor 1"));
        assertTrue(buffers.isSharedWithAnotherEditor("A.java", "editor 2"));
        assertFalse(buffers.isSharedWithAnotherEditor("B.java", "editor 1"), "a document without a buffer");

        buffers.add("A.java", "editor 2", () -> "A"); // Window > Editor > Clone
        assertTrue(buffers.isSharedWithAnotherEditor("A.java", "editor 1"));
    }

    /** The case it is for: a new file's editor switched from its location to the workspace file. */
    @Test
    void moveKeepsTheBufferAndItsEditorsUnderTheNewDocument() {
        DocumentBuffer buffer = numbered(buffers.add("new.txt by location", "editor 1", () -> "/p/new.txt"), 4);

        assertSame(buffer, buffers.move("new.txt by location", "new.txt in the workspace"));

        assertSame(buffer, buffers.add("new.txt in the workspace", "editor 1", () -> "unused"),
                "the attach after the switch finds the same buffer");
        assertEquals(Optional.of(new EditorBuffers.Showing<>("new.txt in the workspace", "editor 1")),
                buffers.editorShowing(4, OPEN));
        assertEquals(List.of(buffer), buffers.remove("editor 1"), "nothing is left under the old document");
    }

    @Test
    void moveOfADocumentWithoutABufferMovesNothing() {
        assertNull(buffers.move("unknown", "other"));
        assertFalse(buffers.isSharedWithAnotherEditor("other", "editor 1"));
    }

    @Test
    void moveOntoADocumentThatHasABufferIsRefused() {
        buffers.add("A", "editor 1", () -> "A");
        buffers.add("B", "editor 2", () -> "B");

        assertThrows(IllegalStateException.class, () -> buffers.move("A", "B"));
        assertTrue(buffers.isSharedWithAnotherEditor("A", "editor 2"), "A keeps its buffer and editor");
    }
}
