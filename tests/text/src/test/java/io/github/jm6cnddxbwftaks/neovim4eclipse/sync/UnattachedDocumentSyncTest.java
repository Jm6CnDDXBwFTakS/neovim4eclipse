// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.BufferChange;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DocumentBuffer;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DocumentBufferPort;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.UiExecutor;
import io.github.jm6cnddxbwftaks.neovim4eclipse.sync.BufferSynchronizerTest.HeldUi;

/**
 * {@link UnattachedDocumentSync} against a real JFace {@link Document} and a fake
 * {@link DocumentBufferPort}: a document whose editor is in the background, and Neovim (or Eclipse)
 * changing it. Queued input-thread work runs when the test says so; Neovim's changes are delivered
 * by calling the registered listener, as the session does on its RPC thread.
 */
class UnattachedDocumentSyncTest {

    /** Records pushes and queued work; delivers Neovim's changes on demand. */
    static final class FakeDocumentBufferPort implements DocumentBufferPort {
        final List<List<String>> pushed = new ArrayList<>();
        final List<Runnable> queued = new ArrayList<>();
        final Map<DocumentBuffer, Consumer<BufferChange>> listeners = new HashMap<>();

        @Override
        public boolean setDocumentBufferLines(DocumentBuffer buffer, List<String> lines) {
            pushed.add(List.copyOf(lines));
            return true;
        }

        @Override
        public void runAsync(String what, Runnable work) {
            queued.add(work);
        }

        @Override
        public void addUnattachedChangeListener(DocumentBuffer buffer, Consumer<BufferChange> listener) {
            listeners.put(buffer, listener);
        }

        @Override
        public void removeUnattachedChangeListener(DocumentBuffer buffer, Consumer<BufferChange> listener) {
            listeners.remove(buffer, listener);
        }

        void runQueued() {
            List<Runnable> work = new ArrayList<>(queued);
            queued.clear();
            work.forEach(Runnable::run);
        }

        void neovimChanged(DocumentBuffer buffer, BufferChange change) {
            Consumer<BufferChange> listener = listeners.get(buffer);
            if (listener != null) {
                listener.accept(change);
            }
        }
    }

    private final IDocument document = new Document("alpha\nbeta\ngamma\n");
    private final DocumentBuffer buffer = new DocumentBuffer("/ws/a.txt");
    private final FakeDocumentBufferPort neovim = new FakeDocumentBufferPort();
    private final UnattachedDocumentSync sync =
            new UnattachedDocumentSync(document, buffer, neovim, UiExecutor.IMMEDIATE);

    @AfterEach
    void close() {
        sync.close();
    }

    @Test
    void neovimsChangeIsAppliedToTheDocumentAndNotPushedBack() {
        sync.start();
        neovim.neovimChanged(buffer, new BufferChange(1, 2, List.of("BETA"))); // :bufdo s/beta/BETA/

        assertEquals("alpha\nBETA\ngamma\n", document.get());
        assertEquals(List.of(), neovim.queued, "the change came from Neovim");
    }

    @Test
    void aFullBufferFromNeovimIsAppliedAsTheSmallestEdit() {
        sync.start();
        neovim.neovimChanged(buffer, new BufferChange(0, -1, List.of("alpha", "beta", "delta")));

        assertEquals("alpha\nbeta\ndelta\n", document.get(), "the final line break is kept");
    }

    /**
     * Applied later on the UI thread, not waited for: the RPC thread goes on reading (the
     * {@code BufEnter}s of {@code :bufdo} are behind the changes), and the order is kept.
     */
    @Test
    void neovimsChangesWaitForTheUiThreadInOrder() {
        HeldUi ui = new HeldUi();
        UnattachedDocumentSync held = new UnattachedDocumentSync(document, buffer, neovim, ui);
        held.start();
        neovim.neovimChanged(buffer, new BufferChange(0, 1, List.of("one")));
        neovim.neovimChanged(buffer, new BufferChange(0, 1, List.of("two")));
        assertEquals("alpha\nbeta\ngamma\n", document.get());

        ui.runHeld();

        assertEquals("two\nbeta\ngamma\n", document.get());
        held.close();
    }

    @Test
    void eclipsesChangesArePushedAsOne() throws Exception {
        sync.start();
        document.replace(0, 5, "ALPHA"); // a rename refactoring touches the file twice
        document.replace(11, 5, "GAMMA");
        assertEquals(1, neovim.queued.size(), "coalesced");

        neovim.runQueued();

        assertEquals(List.of(List.of("ALPHA", "beta", "GAMMA")), neovim.pushed);
    }

    @Test
    void whileAttachedTheSynchronizerPushesAndDetachingPushesOnce() throws Exception {
        sync.start();
        sync.setAttached(true);
        document.replace(0, 5, "typed");
        assertEquals(List.of(), neovim.queued, "BufferSynchronizer's job while attached");

        sync.setAttached(false);
        neovim.runQueued();

        assertEquals(List.of(List.of("typed", "beta", "gamma")), neovim.pushed,
                "e.g. text typed in insert mode that Neovim had not been sent");
    }

    /**
     * The detach push records the document's stamp: attached again with nothing changed, the editor
     * skips its push (DocumentPush). An Eclipse change moves the stamp.
     */
    @Test
    void theDetachPushRecordsTheStampTheBufferEquals() throws Exception {
        sync.start();
        sync.setAttached(true);
        sync.setAttached(false);
        neovim.runQueued();
        long stamp = BufferSynchronizer.stampOf(document);
        assertTrue(buffer.isInSyncAt(stamp));

        sync.setAttached(true);
        document.replace(0, 5, "typed");
        assertFalse(buffer.isInSyncAt(BufferSynchronizer.stampOf(document)), "changed while attached");
    }

    @Test
    void neovimsChangesAreAppliedEvenWhileAttached() {
        // Routed here by the session while the buffer was not attached; applied in order regardless.
        sync.start();
        sync.setAttached(true);
        neovim.neovimChanged(buffer, new BufferChange(0, 1, List.of("ALPHA")));

        assertEquals("ALPHA\nbeta\ngamma\n", document.get());
    }

    @Test
    void aChangeThatNoLongerFitsIsReplacedByEclipsesText() throws Exception {
        sync.start();
        document.set("only\n"); // Eclipse changed it, and Neovim's change below was made before that
        neovim.runQueued();
        neovim.pushed.clear();

        neovim.neovimChanged(buffer, new BufferChange(2, 3, List.of("GAMMA")));
        neovim.runQueued();

        assertEquals("only\n", document.get());
        assertEquals(List.of(List.of("only")), neovim.pushed, "Neovim gets Eclipse's text, so they agree again");
    }

    /**
     * The editor's synchronizer and this one agree on a document's final newline: after {@code o}
     * in the editor on a file without one, its editor is switched away from, and {@code :bufdo}'s
     * {@code o} on the empty last line adds a second empty line, not a replacement of it.
     */
    @Test
    void theFinalNewlineDecidedInTheEditorIsKeptHere() {
        IDocument noFinalNewline = new Document("alpha");
        BufferSynchronizerTest.FakeBufferPort editorPort = new BufferSynchronizerTest.FakeBufferPort();
        try (BufferSynchronizer editor = new BufferSynchronizer(noFinalNewline, editorPort, UiExecutor.IMMEDIATE);
                UnattachedDocumentSync background =
                        new UnattachedDocumentSync(noFinalNewline, buffer, neovim, UiExecutor.IMMEDIATE)) {
            editor.pushDocumentToNeovim();
            editor.startRemoteListening();
            editorPort.neovimChanged(new BufferChange(1, 1, List.of(""))); // o in the editor
            editor.close();
            background.start();

            neovim.neovimChanged(buffer, new BufferChange(2, 2, List.of(""))); // o on line 2, the empty one
            // "alpha" had no line break, so the first one was the platform's (CRLF on Windows).
            String nl = BufferSynchronizer.lineDelimiter(noFinalNewline);
            assertEquals("alpha" + nl + nl, noFinalNewline.get());
        }
    }

    @Test
    void neovimCountsNoLineAfterAFinalLineBreak() {
        assertEquals(1, UnattachedDocumentSync.neovimLineCount(new Document(""), true), "an empty buffer has one line");
        assertEquals(1, UnattachedDocumentSync.neovimLineCount(new Document("a\n"), true));
        assertEquals(2, UnattachedDocumentSync.neovimLineCount(new Document("a\r\nb"), false));
        assertEquals(2, UnattachedDocumentSync.neovimLineCount(new Document("a\n"), false),
                "without a final newline the line break starts an empty last line");
    }

    @Test
    void afterCloseNeitherSideIsMirrored() throws Exception {
        sync.start();
        sync.close();
        assertNull(neovim.listeners.get(buffer));

        document.replace(0, 5, "ALPHA");

        assertEquals(List.of(), neovim.queued);
    }
}
