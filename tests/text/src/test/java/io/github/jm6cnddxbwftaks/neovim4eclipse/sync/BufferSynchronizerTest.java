// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.IDocumentExtension;
import org.eclipse.jface.text.IDocumentListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.BufferChange;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.BufferPort;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.UiExecutor;
import io.github.jm6cnddxbwftaks.neovim4eclipse.editor.VimText;

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
        /** Runs inside each push, as if things happened while the input thread was writing the buffer. */
        Runnable duringPush = () -> { };

        @Override
        public void setBufferLines(List<String> lines) {
            pushed.add(List.copyOf(lines));
            duringPush.run();
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

        final List<Runnable> inputHandled = new ArrayList<>();

        @Override
        public void addInputHandledListener(Runnable listener) {
            inputHandled.add(listener);
        }

        @Override
        public void removeInputHandledListener(Runnable listener) {
            inputHandled.remove(listener);
        }

        /** Says that Neovim has handled the key, as the session does after each queued key. */
        void keyHandled() {
            List.copyOf(inputHandled).forEach(Runnable::run);
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

    /**
     * {@code o} on the last line of a file without a final newline adds one empty line, and the full
     * sync that follows (Neovim sends one when insert mode starts) adds none: the synchronizer
     * remembers from the push that the file had no final newline, although the document now ends
     * with a line break. It used to read that line break as a final newline and show two empty lines.
     */
    @Test
    void oOnTheLastLineOfAFileWithoutAFinalNewlineAddsOneLine() {
        IDocument noFinalNewline = new Document("alpha\nbeta");
        try (BufferSynchronizer noeol = new BufferSynchronizer(noFinalNewline, neovim, UiExecutor.IMMEDIATE)) {
            noeol.pushDocumentToNeovim();
            noeol.startRemoteListening();

            neovim.neovimChanged(new BufferChange(2, 2, List.of(""))); // o
            assertEquals("alpha\nbeta\n", noFinalNewline.get());
            neovim.neovimChanged(new BufferChange(0, -1, List.of("alpha", "beta", "")));
            assertEquals("alpha\nbeta\n", noFinalNewline.get(), "the full sync adds no second empty line");
            neovim.neovimChanged(new BufferChange(3, 3, List.of(""))); // o again, on the empty line
            assertEquals("alpha\nbeta\n\n", noFinalNewline.get());
        }
    }

    /**
     * A push keeps a file without a final newline that way: the empty last line that {@code o} made
     * is sent as a line, so {@code Go} after Esc goes to it and adds a second one, as in Vim.
     */
    @Test
    void aPushKeepsAnEmptyLastLineOfAFileWithoutAFinalNewline() {
        IDocument noFinalNewline = new Document("alpha");
        try (BufferSynchronizer noeol = new BufferSynchronizer(noFinalNewline, neovim, UiExecutor.IMMEDIATE)) {
            noeol.pushDocumentToNeovim();
            noeol.startRemoteListening();
            neovim.neovimChanged(new BufferChange(1, 1, List.of(""))); // o: "alpha\n", no final newline
            noeol.stopRemoteListening();
            noeol.pushDocumentToNeovim(); // Esc
            noeol.startRemoteListening();

            assertEquals(List.of(List.of("alpha"), List.of("alpha", "")), neovim.pushed);
            neovim.neovimChanged(new BufferChange(2, 2, List.of(""))); // Go
            // A document with no line break yet gets the platform's (CRLF on Windows), as when typed.
            String nl = BufferSynchronizer.lineDelimiter(noFinalNewline);
            assertEquals("alpha" + nl + nl, noFinalNewline.get());
        }
    }

    /**
     * A file loses its final newline when Eclipse deletes its last line break (Backspace at the end
     * in insert mode): Neovim's changes are then applied to a file without one.
     */
    @Test
    void deletingTheLastLineBreakInEclipseDropsTheFinalNewline() throws Exception {
        IDocument doc = new Document("alpha\n");
        try (BufferSynchronizer s = new BufferSynchronizer(doc, neovim, UiExecutor.IMMEDIATE)) {
            s.pushDocumentToNeovim();
            doc.replace(5, 1, "");
            s.pushDocumentToNeovim();
            s.startRemoteListening();

            assertEquals(List.of(List.of("alpha"), List.of("alpha")), neovim.pushed);
            neovim.neovimChanged(new BufferChange(1, 1, List.of(""))); // o
            // The document has no line break left, so the new one is the platform's (CRLF on Windows).
            String nl = BufferSynchronizer.lineDelimiter(doc);
            assertEquals("alpha" + nl, doc.get());
            neovim.neovimChanged(new BufferChange(0, -1, List.of("alpha", "")));
            assertEquals("alpha" + nl, doc.get(), "no second empty line");
        }
    }

    /**
     * Undoing a Neovim change brings the document back to the stamp it had before, every time, so
     * an editor saved at that stamp is clean again (see {@link UndoSteps}). Without a new undo step
     * per change, the second round restored a stale stamp and the file stayed marked as changed.
     */
    @Test
    void undoingANeovimChangeRestoresTheStampBeforeIt() throws Exception {
        IDocument doc = new Document("alpha\nbeta\ngamma\n");
        org.eclipse.text.undo.DocumentUndoManagerRegistry.connect(doc);
        try (BufferSynchronizer s = new BufferSynchronizer(doc, neovim, UiExecutor.IMMEDIATE)) {
            org.eclipse.text.undo.IDocumentUndoManager undo =
                    org.eclipse.text.undo.DocumentUndoManagerRegistry.getDocumentUndoManager(doc);
            undo.connect(this); // as the editor's text viewer does
            s.pushDocumentToNeovim();
            s.startRemoteListening();
            for (int round = 0; round < 3; round++) {
                long saved = ((IDocumentExtension4) doc).getModificationStamp(); // as if saved here
                neovim.neovimChanged(new BufferChange(2, 2, List.of("beta"))); // yyp on line 2
                neovim.keyHandled();
                assertEquals("alpha\nbeta\nbeta\ngamma\n", doc.get());
                undo.undo(); // Ctrl+Z
                assertEquals("alpha\nbeta\ngamma\n", doc.get());
                assertEquals(saved, ((IDocumentExtension4) doc).getModificationStamp(), "round " + round);
            }
        } finally {
            org.eclipse.text.undo.DocumentUndoManagerRegistry.getDocumentUndoManager(doc).disconnect(this);
            org.eclipse.text.undo.DocumentUndoManagerRegistry.disconnect(doc);
        }
    }

    /**
     * The changes of one Neovim command are one undo step: {@code J} changes line 1 and deletes line
     * 2, and one Ctrl+Z undoes both. The next command is a step of its own.
     */
    @Test
    void theChangesOfOneNeovimCommandAreOneUndoStep() throws Exception {
        IDocument doc = new Document("alpha\nbeta\ngamma\n");
        org.eclipse.text.undo.DocumentUndoManagerRegistry.connect(doc);
        org.eclipse.text.undo.IDocumentUndoManager undo =
                org.eclipse.text.undo.DocumentUndoManagerRegistry.getDocumentUndoManager(doc);
        undo.connect(this);
        try (BufferSynchronizer s = new BufferSynchronizer(doc, neovim, UiExecutor.IMMEDIATE)) {
            s.pushDocumentToNeovim();
            s.startRemoteListening();
            neovim.neovimChanged(new BufferChange(0, 1, List.of("alpha beta"))); // J: two changes
            neovim.neovimChanged(new BufferChange(1, 2, List.of()));
            neovim.keyHandled();
            neovim.neovimChanged(new BufferChange(1, 2, List.of())); // dd on gamma: the next command
            neovim.keyHandled();
            assertEquals("alpha beta\n", doc.get());

            undo.undo();
            assertEquals("alpha beta\ngamma\n", doc.get(), "one Ctrl+Z undoes dd");
            undo.undo();
            assertEquals("alpha\nbeta\ngamma\n", doc.get(), "one more undoes all of J");
        } finally {
            undo.disconnect(this);
            org.eclipse.text.undo.DocumentUndoManagerRegistry.disconnect(doc);
        }
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

    // ---- bursts: several edits in a row, from either side ----

    @Test
    void aBurstOfEclipseEditsQueuesOnePushWithTheFinalText() throws Exception {
        sync.pushLocalChanges(() -> true, null);
        for (int i = 0; i < 20; i++) {
            document.replace(0, 0, "x"); // e.g. a formatter making many small edits
        }
        assertEquals(1, neovim.queued.size());
        neovim.runQueued();
        assertEquals(List.of(List.of("x".repeat(20) + "alpha", "beta")), neovim.pushed);
    }

    @Test
    void anEditWhileAPushRunsQueuesOneMore() throws Exception {
        sync.pushLocalChanges(() -> true, null);
        neovim.duringPush = () -> {
            neovim.duringPush = () -> { };
            replace(0, 5, "gamma"); // Eclipse changes the document while the push is on its way
        };
        document.replace(0, 5, "omega");
        neovim.runQueued();
        assertEquals(1, neovim.queued.size(), "the later edit must not be lost");
        neovim.runQueued();
        assertEquals(List.of(List.of("omega", "beta"), List.of("gamma", "beta")), neovim.pushed);
        assertTrue(neovim.queued.isEmpty(), "no push loop");
    }

    @Test
    void manyNeovimChangesInARowApplyInOrderWithoutEchoes() {
        sync.pushLocalChanges(() -> true, null);
        sync.startRemoteListening();
        for (int i = 1; i <= 50; i++) {
            neovim.neovimChanged(new BufferChange(0, 1, List.of("line " + i))); // e.g. a macro's steps
        }
        assertEquals("line 50\nbeta\n", document.get());
        assertTrue(neovim.queued.isEmpty());
    }

    @Test
    void neovimChangesAddingAndRemovingLinesKeepTheRestInPlace() {
        IDocument doc = VimText.parse(VimText.SAMPLE).document();
        try (BufferSynchronizer s = new BufferSynchronizer(doc, neovim, UiExecutor.IMMEDIATE)) {
            s.startRemoteListening();
            neovim.neovimChanged(new BufferChange(1, 1, List.of("new", "lines"))); // o, twice
            neovim.neovimChanged(new BufferChange(0, 1, List.of()));              // dd on line 1
            neovim.neovimChanged(new BufferChange(3, 4, List.of("aé ü!")));       // A! on "aé ü"
            assertEquals("new\nlines\n\tindented with a tab\naé ü!\nsmile 😀 end\n\nlast\n", doc.get());
        }
    }

    @Test
    void anEditMadeInReactionToNeovimsChangeIsPushedBack() {
        // Linked mode (a rename) mirrors each change into the other occurrences with a
        // post-notification replace, inside Neovim's own change. Neovim must learn about the mirror.
        IDocument doc = new Document("foo = foo\n");
        try (BufferSynchronizer s = new BufferSynchronizer(doc, neovim, UiExecutor.IMMEDIATE)) {
            s.pushLocalChanges(() -> true, null);
            s.startRemoteListening();
            mirrorFirstWordInto(doc, 6);
            neovim.neovimChanged(new BufferChange(0, 1, List.of("bar = foo")));
            assertEquals("bar = bar\n", doc.get());
            assertEquals(1, neovim.queued.size());

            // Before the push runs, Neovim's own full re-sync arrives, still without the mirror. It is
            // stale: applying it would undo the mirror.
            neovim.neovimChanged(new BufferChange(0, -1, List.of("bar = foo")));
            assertEquals("bar = bar\n", doc.get());

            neovim.runQueued();
            assertEquals(List.of(List.of("bar = bar")), neovim.pushed);
            assertTrue(neovim.queued.isEmpty(), "no push loop");

            // After the push Neovim is up to date again, so full re-syncs apply.
            neovim.neovimChanged(new BufferChange(0, -1, List.of("baz = baz")));
            assertEquals("baz = baz\n", doc.get());
        }
    }

    @Test
    void thePushesOwnEchoIsNotApplied() throws Exception {
        sync.pushLocalChanges(() -> true, null);
        sync.startRemoteListening();
        neovim.duringPush = () -> neovim.neovimChanged(new BufferChange(0, -1, List.of("stale")));
        document.replace(0, 5, "gamma");
        neovim.runQueued();
        assertEquals("gamma\nbeta\n", document.get());
        assertEquals(1, neovim.listeners.size(), "listening resumes after the push");
    }

    @Test
    void closingInsideADocumentEventDropsTheQueuedPushWithoutErrors() throws Exception {
        sync.pushLocalChanges(() -> true, null);
        document.addDocumentListener(new IDocumentListener() {
            @Override
            public void documentAboutToBeChanged(DocumentEvent event) {
            }

            @Override
            public void documentChanged(DocumentEvent event) {
                sync.close(); // the editor closes while listeners are being notified
            }
        });
        document.replace(0, 5, "gamma");
        document.replace(0, 5, "delta"); // after close: nothing queued
        assertEquals(1, neovim.queued.size());
        neovim.runQueued();
        assertEquals(List.of(), neovim.pushed);
    }

    // ---- threads: the push reads the document on the UI thread ----

    /** A UI thread elsewhere that runs {@code syncExec} work at once, and tells when it does. */
    static final class OtherThreadUi implements UiExecutor {
        boolean available = true;
        boolean running;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public boolean isUiThread() {
            return running;
        }

        @Override
        public void asyncExec(Runnable work) {
            syncExec(work);
        }

        @Override
        public void syncExec(Runnable work) {
            running = true;
            try {
                work.run();
            } finally {
                running = false;
            }
        }
    }

    /** A document that records, for each read of its text, whether the UI thread was reading. */
    private static IDocument documentRecordingReads(String text, OtherThreadUi ui, List<Boolean> readsOnUi) {
        return new Document(text) {
            @Override
            public String get() {
                readsOnUi.add(ui.isUiThread());
                return super.get();
            }
        };
    }

    @Test
    void aPushFromTheInputThreadReadsTheDocumentOnTheUiThread() {
        OtherThreadUi ui = new OtherThreadUi();
        List<Boolean> readsOnUi = new ArrayList<>();
        IDocument doc = documentRecordingReads("alpha\nbeta\n", ui, readsOnUi);
        try (BufferSynchronizer s = new BufferSynchronizer(doc, neovim, ui)) {
            s.pushDocumentToNeovim(); // the test thread plays the session's input thread
            assertEquals(List.of(List.of("alpha", "beta")), neovim.pushed);
            assertEquals(List.of(true), readsOnUi);
        }
    }

    @Test
    void aQueuedPushAfterAnEclipseChangeAlsoReadsOnTheUiThread() throws Exception {
        OtherThreadUi ui = new OtherThreadUi();
        List<Boolean> readsOnUi = new ArrayList<>();
        IDocument doc = documentRecordingReads("alpha\n", ui, readsOnUi);
        try (BufferSynchronizer s = new BufferSynchronizer(doc, neovim, ui)) {
            s.pushLocalChanges(() -> true, null);
            ui.syncExec(() -> replace(doc, 0, 5, "gamma")); // Eclipse's edit, on the UI thread
            readsOnUi.clear();
            neovim.runQueued(); // on the input thread
            assertEquals(List.of(List.of("gamma")), neovim.pushed);
            assertEquals(List.of(true), readsOnUi);
        }
    }

    @Test
    void withoutAUiThereIsNoPush() {
        OtherThreadUi ui = new OtherThreadUi();
        ui.available = false;
        try (BufferSynchronizer s = new BufferSynchronizer(document, neovim, ui)) {
            s.pushDocumentToNeovim();
            assertEquals(List.of(), neovim.pushed);
        }
    }

    /** Once: when the first word changes, copies it to {@code mirrorAt}, as linked mode would. */
    private static void mirrorFirstWordInto(IDocument doc, int mirrorAt) {
        doc.addDocumentListener(new IDocumentListener() {
            private boolean done;

            @Override
            public void documentAboutToBeChanged(DocumentEvent event) {
            }

            @Override
            public void documentChanged(DocumentEvent event) {
                if (done || event.getOffset() >= mirrorAt) {
                    return;
                }
                done = true;
                String word = event.getText();
                ((IDocumentExtension) doc).registerPostNotificationReplace(this,
                        (document, owner) -> replace(document, mirrorAt, word.length(), word));
            }
        });
    }

    private void replace(int offset, int length, String text) {
        replace(document, offset, length, text);
    }

    private static void replace(IDocument doc, int offset, int length, String text) {
        try {
            doc.replace(offset, length, text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
