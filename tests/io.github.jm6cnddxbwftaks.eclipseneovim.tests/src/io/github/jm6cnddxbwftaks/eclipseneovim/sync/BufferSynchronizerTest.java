// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.BufferChange;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.BufferPort;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.UiExecutor;

/**
 * {@link BufferSynchronizer} against a real JFace {@link Document} and a fake {@link BufferPort}: no
 * Neovim process. The fake keeps queued work until the test runs it, the way the session's input
 * thread would run it later; Neovim's changes are delivered by calling the registered listeners.
 * The UI thread is {@link UiExecutor#IMMEDIATE}, or {@link HeldUi} to act as if Neovim's change
 * came from a session thread and is still waiting for the UI thread.
 */
class BufferSynchronizerTest {

    /** Records pushes and queued work; delivers buffer changes on demand. */
    static final class FakeBufferPort implements BufferPort {
        final List<List<String>> pushed = new ArrayList<>();
        final List<Runnable> queued = new ArrayList<>();
        final List<Consumer<BufferChange>> listeners = new ArrayList<>();

        @Override
        public void setBufferLines(List<String> lines) {
            pushed.add(List.copyOf(lines));
        }

        @Override
        public void runAsync(String what, Runnable work) {
            queued.add(work);
        }

        @Override
        public void addBufferChangeListener(Consumer<BufferChange> listener) {
            listeners.add(listener);
        }

        @Override
        public void removeBufferChangeListener(Consumer<BufferChange> listener) {
            listeners.remove(listener);
        }

        /** Runs the queued work in order, as the input thread would. */
        void runQueued() {
            List<Runnable> work = new ArrayList<>(queued);
            queued.clear();
            work.forEach(Runnable::run);
        }

        /** Delivers a change as Neovim's buffer event would. */
        void neovimChanged(BufferChange change) {
            List.copyOf(listeners).forEach(l -> l.accept(change));
        }
    }

    /** A UI thread that is never the caller's: work waits until {@link #runHeld()}. */
    static final class HeldUi implements UiExecutor {
        final List<Runnable> held = new ArrayList<>();
        boolean available = true;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public boolean isUiThread() {
            return false;
        }

        @Override
        public void asyncExec(Runnable work) {
            held.add(work);
        }

        @Override
        public void syncExec(Runnable work) {
            held.add(work); // a real syncExec would wait; the test decides when the UI thread gets to it
        }

        void runHeld() {
            List<Runnable> work = new ArrayList<>(held);
            held.clear();
            work.forEach(Runnable::run);
        }
    }

    private final IDocument document = new Document("alpha\nbeta\n");
    private final FakeBufferPort neovim = new FakeBufferPort();
    private final BufferSynchronizer sync = new BufferSynchronizer(document, neovim, UiExecutor.IMMEDIATE);

    @AfterEach
    void close() {
        sync.close();
    }

    @Test
    void aPushSendsTheDocumentsLines() {
        sync.pushDocumentToNeovim();
        assertEquals(List.of(List.of("alpha", "beta")), neovim.pushed);
    }

    @Test
    void anEclipseChangeQueuesAPushThatRunsTheAfterAction() throws Exception {
        List<String> after = new ArrayList<>();
        sync.pushLocalChanges(() -> true, () -> after.add("after push"));
        document.replace(0, 5, "gamma");
        assertEquals(List.of(), neovim.pushed); // queued, not yet run
        neovim.runQueued();
        assertEquals(List.of(List.of("gamma", "beta")), neovim.pushed);
        assertEquals(List.of("after push"), after);
    }

    @Test
    void aClosedGateQueuesNothing() throws Exception {
        sync.pushLocalChanges(() -> false, null);
        document.replace(0, 5, "gamma");
        assertEquals(List.of(), neovim.queued);
    }

    @Test
    void aNeovimChangeIsAppliedToTheDocumentWithoutAnEcho() {
        sync.pushLocalChanges(() -> true, null);
        sync.startRemoteListening();
        neovim.neovimChanged(new BufferChange(1, 2, List.of("BETA")));
        assertEquals("alpha\nBETA\n", document.get());
        assertEquals(List.of(), neovim.queued); // Neovim's own change is not pushed back
    }

    @Test
    void closeUnregistersFromTheSession() {
        sync.startRemoteListening();
        assertEquals(1, neovim.listeners.size());
        sync.close();
        assertTrue(neovim.listeners.isEmpty());
    }

    @Test
    void aNeovimChangeFromASessionThreadIsAppliedOnTheUiThread() {
        HeldUi ui = new HeldUi();
        try (BufferSynchronizer offThread = new BufferSynchronizer(document, neovim, ui)) {
            offThread.startRemoteListening();
            neovim.neovimChanged(new BufferChange(0, 1, List.of("ALPHA")));
            assertEquals("alpha\nbeta\n", document.get()); // not on the session thread
            ui.runHeld();
            assertEquals("ALPHA\nbeta\n", document.get());
        }
    }

    @Test
    void aChangeStillWaitingForTheUiThreadWhenTheEditorClosesIsDropped() {
        HeldUi ui = new HeldUi();
        BufferSynchronizer offThread = new BufferSynchronizer(document, neovim, ui);
        offThread.startRemoteListening();
        neovim.neovimChanged(new BufferChange(0, 1, List.of("ALPHA")));
        offThread.close();
        ui.runHeld();
        assertEquals("alpha\nbeta\n", document.get());
    }

    @Test
    void withoutAUiNeovimsChangesAreDropped() {
        HeldUi ui = new HeldUi();
        ui.available = false;
        try (BufferSynchronizer offThread = new BufferSynchronizer(document, neovim, ui)) {
            offThread.startRemoteListening();
            neovim.neovimChanged(new BufferChange(0, 1, List.of("ALPHA")));
            assertTrue(ui.held.isEmpty());
        }
    }
}
