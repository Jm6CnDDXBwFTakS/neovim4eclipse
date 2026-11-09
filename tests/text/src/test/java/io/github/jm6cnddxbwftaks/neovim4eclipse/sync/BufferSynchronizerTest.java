// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;

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
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DocumentBuffer;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.UiExecutor;
import io.github.jm6cnddxbwftaks.neovim4eclipse.editor.VimText;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ChangedLines;

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

        /**
         * Neovim's buffer as the next whole push finds it, which the push answers; {@code null}: as
         * a push that loaded a new buffer, with nothing to compare. Each push makes it the pushed
         * lines, as Neovim's buffer would be then.
         */
        List<String> neovimLines;

        @Override
        public List<String> setBufferLines(List<String> lines) {
            pushed.add(List.copyOf(lines));
            duringPush.run();
            List<String> before = neovimLines;
            if (before != null) {
                neovimLines = List.copyOf(lines.isEmpty() ? List.of("") : lines);
            }
            return before;
        }

        /** Each partial push, as "first|unchangedAtEnd|lines". */
        final List<String> regions = new ArrayList<>();
        /** Whether a partial push is accepted (false: as a buffer not loaded yet). */
        boolean regionsAccepted = true;

        @Override
        public boolean setBufferRegion(int first, int unchangedAtEnd, List<String> lines) {
            if (!regionsAccepted) {
                return false;
            }
            regions.add(first + "|" + unchangedAtEnd + "|" + lines);
            return true;
        }

        /** Each split of Neovim's lines at carriage returns: the parts by Neovim line. */
        final List<Map<Integer, List<String>>> splits = new ArrayList<>();
        /** The buffer each split was for ({@code null}: the attached one). */
        final List<DocumentBuffer> splitBuffers = new ArrayList<>();
        /** Runs inside each split: Neovim's line events for it. */
        Runnable duringSplit = () -> { };

        @Override
        public void splitLineBreaks(DocumentBuffer buffer, Map<Integer, List<String>> parts) {
            splits.add(Map.copyOf(parts));
            splitBuffers.add(buffer);
            duringSplit.run();
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

    {
        sync.setCompareWholeUpToLines(0); // the region push even on these short documents
    }

    @AfterEach
    void close() {
        sync.close();
    }

    // ---- the push of the changed lines only (INSERT_CTRL_KEYS.md, section 4) ----

    @Test
    void theFirstChangedLinesPushWritesTheWholeDocument() {
        sync.pushChangedLinesToNeovim();
        assertEquals(List.of(List.of("alpha", "beta")), neovim.pushed, "nothing is known yet");
        assertEquals(List.of(), neovim.regions);
    }

    /** One line typed in: only that line goes; Neovim's last line ("beta") stays as it is. */
    @Test
    void aChangedLinesPushWritesOnlyTheTypedLines() throws Exception {
        document.set("alpha\nbeta\ngamma\n");
        sync.pushDocumentToNeovim();
        document.replace(8, 0, "XY"); // "beXYta"
        sync.pushChangedLinesToNeovim();
        assertEquals(List.of("1|1|[beXYta]"), neovim.regions);
        assertEquals(1, neovim.pushed.size(), "only the full push before");
    }

    @Test
    void enterTypedPushesBothLinesItMade() throws Exception {
        document.set("alpha\nbeta\ngamma\n");
        sync.pushDocumentToNeovim();
        document.replace(8, 0, "\n"); // "be" / "ta"
        sync.pushChangedLinesToNeovim();
        assertEquals(List.of("1|1|[be, ta]"), neovim.regions);
    }

    /** A short document is compared whole: that repairs a difference anywhere. */
    @Test
    void aShortDocumentIsComparedWhole() throws Exception {
        sync.setCompareWholeUpToLines(ChangedLines.COMPARE_WHOLE_UP_TO_LINES);
        document.set("alpha\nbeta\ngamma\n");
        sync.pushDocumentToNeovim();
        document.replace(8, 0, "XY");
        sync.pushChangedLinesToNeovim();
        assertEquals(List.of(), neovim.regions);
        assertEquals(List.of("alpha", "beXYta", "gamma"), neovim.pushed.get(neovim.pushed.size() - 1));
    }

    // ---- a push that repairs a drift is reported (TextRepairs) ----

    /** Compares whole, as on short documents, with Neovim's buffer equal to the document. */
    private void startComparedWhole(String text) {
        sync.setCompareWholeUpToLines(ChangedLines.COMPARE_WHOLE_UP_TO_LINES);
        document.set(text);
        neovim.neovimLines = List.of(); // known from here on: the push below makes it the document's
        sync.pushDocumentToNeovim();
    }

    /** Neovim's buffer drifted in line 3, Eclipse typed in line 1: the push at Esc repairs line 3. */
    @Test
    void aPushAtEscThatRepairsAnotherLineIsReported() throws Exception {
        startComparedWhole("alpha\nbeta\ngamma\n");
        neovim.neovimLines = List.of("alpha", "beta", "GAMMA");
        document.replace(0, 0, "X");
        try (ExpectedLog log = ExpectedLog.of(TextRepairs.class, Level.FINE)) {
            sync.pushChangedLinesToNeovim();
            log.assertLogged(Level.FINE, "its line 3 differed from the document's line 3");
            // The lines are for the tests, in the record's own fields: no log formatter prints
            // them (an XMLFormatter prints every parameter of a message without placeholders).
            TextRepairs.RepairRecord repair = (TextRepairs.RepairRecord) log.records().get(0);
            assertEquals("GAMMA", repair.neovimLine());
            assertEquals("gamma", repair.documentLine());
            assertNull(repair.getParameters());
            assertFalse(repair.getMessage().contains("amma"), "no file text in the message");
        }
        assertEquals(List.of("Xalpha", "beta", "gamma"), neovim.neovimLines, "repaired all the same");
    }

    @Test
    void aPushAtEscOfOnlyTheTypedLinesReportsNothing() throws Exception {
        startComparedWhole("alpha\nbeta\ngamma\n");
        document.replace(6, 0, "X\nY");
        try (ExpectedLog log = ExpectedLog.of(TextRepairs.class, Level.FINE)) {
            sync.pushChangedLinesToNeovim();
            log.assertNothingAtOrAbove(Level.FINE);
        }
        assertEquals(List.of("alpha", "X", "Ybeta", "gamma"), neovim.neovimLines);
    }

    /** The push of Eclipse's own change (a format, say) is checked too. */
    @Test
    void aPushOfAnEclipseChangeThatRepairsAnotherLineIsReported() throws Exception {
        startComparedWhole("alpha\nbeta\ngamma\n");
        sync.pushLocalChanges(() -> true, null);
        neovim.neovimLines = List.of("ALPHA", "beta", "gamma");
        document.replace(document.getLineOffset(2), 5, "GAMMA");
        try (ExpectedLog log = ExpectedLog.of(TextRepairs.class, Level.FINE)) {
            neovim.runQueued();
            log.assertLogged(Level.FINE, "its line 1 differed");
        }
    }

    /** The push at attach carries what changed while Neovim was not followed: never a repair. */
    @Test
    void thePushAtAttachReportsNothing() throws Exception {
        startComparedWhole("alpha\nbeta\ngamma\n");
        neovim.neovimLines = List.of("ALPHA", "beta", "gamma");
        try (ExpectedLog log = ExpectedLog.of(TextRepairs.class, Level.FINE)) {
            sync.pushDocumentToNeovim();
            log.assertNothingAtOrAbove(Level.FINE);
        }
    }

    /** Neovim's own change, applied to the document, explains its lines: no repair. */
    @Test
    void aNeovimChangeAppliedToTheDocumentIsNoRepair() throws Exception {
        startComparedWhole("alpha\nbeta\ngamma\n");
        sync.startRemoteListening();
        neovim.neovimLines = List.of("alpha", "beta", "GAMMA");
        neovim.neovimChanged(new BufferChange(2, 3, List.of("GAMMA")));
        document.replace(0, 0, "X");
        try (ExpectedLog log = ExpectedLog.of(TextRepairs.class, Level.FINE)) {
            sync.pushChangedLinesToNeovim();
            log.assertNothingAtOrAbove(Level.FINE);
        }
        assertEquals("Xalpha\nbeta\nGAMMA\n", document.get());
    }

    /** A change of no line only runs its after-apply action, in order: the cheap sync-point handshake. */
    @Test
    void aMarkerChangesNothingAndRunsItsAction() {
        List<String> ran = new ArrayList<>();
        sync.startRemoteListening();
        neovim.neovimChanged(BufferChange.marker(() -> ran.add("after")));
        assertEquals(List.of("after"), ran);
        assertEquals("alpha\nbeta\n", document.get());
    }

    @Test
    void nothingChangedPushesNothing() {
        sync.pushDocumentToNeovim();
        sync.pushChangedLinesToNeovim();
        assertEquals(1, neovim.pushed.size());
        assertEquals(List.of(), neovim.regions);
    }

    /** The last line may have gained or lost a final line break: the whole document. */
    @Test
    void aChangeOfTheLastLinePushesTheWholeDocument() throws Exception {
        document.set("alpha\nbeta");
        sync.pushDocumentToNeovim();
        document.replace(document.getLength(), 0, "!");
        sync.pushChangedLinesToNeovim();
        assertEquals(List.of(List.of("alpha", "beta"), List.of("alpha", "beta!")), neovim.pushed);
        assertEquals(List.of(), neovim.regions);
    }

    /** A buffer that cannot be written in part (its first push, say) gets the whole document. */
    @Test
    void aRefusedPartialPushFallsBackToTheWholeDocument() throws Exception {
        document.set("alpha\nbeta\ngamma\n");
        sync.pushDocumentToNeovim();
        neovim.regionsAccepted = false;
        document.replace(0, 0, "X");
        sync.pushChangedLinesToNeovim();
        assertEquals(List.of("Xalpha", "beta", "gamma"), neovim.pushed.get(neovim.pushed.size() - 1));
    }

    /** After a partial push, the next one starts from there: only what changed since. */
    @Test
    void eachPartialPushStartsOver() throws Exception {
        document.set("alpha\nbeta\ngamma\n");
        sync.pushDocumentToNeovim();
        document.replace(0, 0, "X");
        sync.pushChangedLinesToNeovim();
        document.replace(document.getLineOffset(2), 0, "Y");
        sync.pushChangedLinesToNeovim();
        assertEquals(List.of("0|2|[Xalpha]", "2|0|[Ygamma]"), neovim.regions);
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
     * A push that fails leaves Neovim without the document (an empty buffer, on the first load), so
     * Neovim's next whole-buffer text is not applied: it would empty the Eclipse document, which a
     * save then writes (file-by-file security review of 0.2.1: a file of 33,000 lines failed to
     * load). The next push sends everything again.
     */
    @Test
    void afterAFailedPushNeovimsWholeBufferDoesNotReplaceTheDocument() {
        IDocument doc = new Document("keep\nthis\n");
        neovim.duringPush = () -> {
            throw new IllegalStateException("Lua: stack overflow (expected by the test)");
        };
        try (BufferSynchronizer sync = new BufferSynchronizer(doc, neovim, UiExecutor.IMMEDIATE);
                ExpectedLog log = ExpectedLog.of(BufferSynchronizer.class)) {
            sync.pushDocumentToNeovim();
            log.assertLogged(Level.SEVERE, "Failed to push document to Neovim");
            sync.startRemoteListening();
            neovim.neovimChanged(new BufferChange(0, -1, List.of(""))); // Neovim's empty buffer
            assertEquals("keep\nthis\n", doc.get(), "not emptied");

            neovim.duringPush = () -> { };
            sync.pushDocumentToNeovim();
            assertEquals(List.of("keep", "this"), neovim.pushed.get(neovim.pushed.size() - 1), "pushed again");
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

    /** A "\r" Neovim keeps inside line 1 is not the file's delimiter; a file of "\r" alone keeps it. */
    @Test
    void theDelimiterLooksPastACarriageReturnInsideALine() {
        assertEquals("\n", BufferSynchronizer.lineDelimiter(new Document("\ra\nb\n")));
        assertEquals("\r\n", BufferSynchronizer.lineDelimiter(new Document("x\ry\r\nb\r\n")));
        assertEquals("\r", BufferSynchronizer.lineDelimiter(new Document("a\rb\r")));
        assertEquals("\n", BufferSynchronizer.lineDelimiter(new Document("a\nb\n")));
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

    /**
     * Ctrl-V Ctrl-M at the start of line 1 of "a\nb\nc\n" (as line 0 of Neovim's buffer "x"): Neovim's
     * line "\ra" is two lines in Eclipse. Without a push back, Neovim's next {@code j dd} (delete
     * line 2, "b") would delete "a" here. Neovim splits its line as Eclipse does, and its later
     * changes land on the right lines; its full sync before the split has the same text, and the
     * document keeps its "\n" line breaks (the delimiter is not taken from the "\r"'s line).
     */
    @Test
    void aCarriageReturnInsideANeovimLineIsSplitInNeovimSoBothCountTheSameLines() {
        IDocument doc = new Document("a\nb\nc\n");
        try (BufferSynchronizer s = new BufferSynchronizer(doc, neovim, UiExecutor.IMMEDIATE)) {
            s.pushLocalChanges(() -> true, null);
            s.startRemoteListening();
            neovim.neovimChanged(new BufferChange(0, 1, List.of("\ra")));
            assertEquals("\ra\nb\nc\n", doc.get(), "the carriage return is kept, as Neovim has it");
            assertEquals(1, neovim.queued.size(), "Neovim's split is queued");

            neovim.neovimChanged(new BufferChange(0, -1, List.of("\ra", "b", "c")));
            assertEquals("\ra\nb\nc\n", doc.get(), "the full sync changes nothing: its lines are joined with \"\\n\"");
            assertEquals(1, neovim.queued.size(), "one split for both");

            neovim.runQueued();
            assertEquals(List.of(Map.of(0, List.of("", "a"))), neovim.splits, "the document's lines for Neovim's line 0");
            assertEquals(List.of(), neovim.pushed, "Neovim splits its lines itself");
            neovim.neovimChanged(new BufferChange(0, 1, List.of("", "a"))); // the split's line event
            assertEquals("\ra\nb\nc\n", doc.get(), "the same lines");
            assertTrue(neovim.queued.isEmpty(), "no split loop");

            // Neovim now has "", "a", "b", "c": "dd" on "b" is line 2 on both sides.
            neovim.neovimChanged(new BufferChange(2, 3, List.of()));
            assertEquals("\ra\nc\n", doc.get());
        }
    }

    /**
     * The split names the synchronizer's own buffer, not "the attached one": it runs on the input
     * thread after the keys before it, and by then the user may have switched to another editor,
     * whose buffer would be tried instead (and this one never split).
     */
    @Test
    void theSplitIsForTheSynchronizersOwnBuffer() {
        IDocument doc = new Document("a\nb\n");
        DocumentBuffer buffer = new DocumentBuffer("/ws/p/a.txt");
        try (BufferSynchronizer s = new BufferSynchronizer(doc, buffer, neovim, UiExecutor.IMMEDIATE)) {
            s.pushLocalChanges(() -> true, null);
            s.startRemoteListening();
            neovim.neovimChanged(new BufferChange(0, 1, List.of("\ra")));
            neovim.runQueued();
            assertEquals(List.of(Map.of(0, List.of("", "a"))), neovim.splits);
            assertEquals(1, neovim.splitBuffers.size());
            assertSame(buffer, neovim.splitBuffers.get(0));
        }
    }

    /**
     * {@code qa i Ctrl-V Ctrl-M b Esc q} on "a" (line 3 of x, y, z, a, c): while a macro is recorded
     * Neovim types the whole insert, its keys in flight. The split is queued behind them, so the
     * line events of the keys before it use Neovim's numbers, one line fewer than Eclipse's below the
     * split; they land on the right lines all the same, and so does the full sync after Esc. Before,
     * the event for "b" replaced only Eclipse's line 3 and left a second "a", and the full sync after
     * Esc was skipped. No push is needed (the insert's gate stays closed throughout).
     */
    @Test
    void aSplitLineInANeovimDrivenInsertKeepsTheLinesUntilNeovimSplitsIt() {
        IDocument doc = new Document("x\ny\nz\na\nc\n");
        try (BufferSynchronizer s = new BufferSynchronizer(doc, neovim, UiExecutor.IMMEDIATE)) {
            s.pushLocalChanges(() -> false, null); // insert mode
            s.startRemoteListening();
            neovim.neovimChanged(new BufferChange(3, 4, List.of("\ra"))); // Ctrl-V Ctrl-M
            neovim.neovimChanged(new BufferChange(3, 4, List.of("\rba"))); // b, before the split ran
            assertEquals("x\ny\nz\n\rba\nc\n", doc.get());
            neovim.neovimChanged(new BufferChange(4, 4, List.of("d"))); // o d: below Neovim's line 3
            assertEquals("x\ny\nz\n\rba\nd\nc\n", doc.get());
            neovim.neovimChanged(new BufferChange(0, -1, List.of("x", "y", "z", "\rba", "d", "c"))); // after Esc
            assertEquals("x\ny\nz\n\rba\nd\nc\n", doc.get(), "Neovim's full sync is applied: it has the same text");

            neovim.runQueued();
            assertEquals(List.of(Map.of(3, List.of("", "ba"))), neovim.splits);
            neovim.neovimChanged(new BufferChange(3, 4, List.of("", "ba"))); // the split's line event
            neovim.neovimChanged(new BufferChange(6, 7, List.of())); // dd on "c": the same line on both sides
            assertEquals("x\ny\nz\n\rba\nd\n", doc.get());
            assertEquals(List.of(), neovim.pushed);
        }
    }

    /**
     * {@code Ctrl-R a} in an insert Eclipse types, register a holding "1\r2": Neovim drives while it
     * waits for the register name, inserts the register and hands typing back to Eclipse after a full
     * sync. That sync is applied (it used to be skipped once the split marked Neovim behind), but
     * lets Eclipse type only once Neovim has split the line, so that the caret is where the split
     * put Neovim's cursor (Eclipse's line 1, not line 0) when Eclipse types.
     */
    @Test
    void aSplitLineBeforeAHandBackLetsEclipseTypeOnlyAfterNeovimSplitIt() {
        IDocument doc = new Document("a\nb\n");
        List<String> order = new ArrayList<>();
        try (BufferSynchronizer s = new BufferSynchronizer(doc, neovim, UiExecutor.IMMEDIATE)) {
            s.pushLocalChanges(() -> false, null); // insert mode throughout
            s.setCaretRestorer(() -> order.add("caret"));
            s.startRemoteListening();
            neovim.neovimChanged(new BufferChange(0, 1, List.of("a1\r2")));
            neovim.neovimChanged(new BufferChange(0, -1, List.of("a1\r2", "b"), () -> order.add("Eclipse types")));
            assertEquals("a1\r2\nb\n", doc.get());
            assertTrue(!order.contains("Eclipse types"), "not before the split: " + order);

            order.clear();
            neovim.duringSplit = () -> neovim.neovimChanged(new BufferChange(0, 1, List.of("a1", "2")));
            neovim.runQueued();
            assertEquals(List.of("caret", "caret", "Eclipse types"), order,
                    "the split's event, then the caret on the cursor the split reported, then the typing");
            assertEquals("a1\r2\nb\n", doc.get());
            neovim.neovimChanged(new BufferChange(2, 3, List.of("B"))); // Neovim counts 3 lines now
            assertEquals("a1\r2\nB\n", doc.get());
        }
    }

    /**
     * Ctrl-V Ctrl-M at the end of "beta1", then "2" and "x". A "\r" typed before the "\n" is a line
     * of its own in a JFace document (its line tracker does not join them after an insert), so Neovim's
     * one line is two here at once; each key's line event still lands on its lines, and the "2" goes
     * between the carriage return and the newline. The split Neovim is asked for has the document's
     * lines.
     */
    @Test
    void aCarriageReturnTypedAtALinesEndThenTextAfterItGivesNeovimsText() {
        IDocument doc = new Document("alpha\nbeta1\ngamma\n");
        try (BufferSynchronizer s = new BufferSynchronizer(doc, neovim, UiExecutor.IMMEDIATE)) {
            s.pushLocalChanges(() -> false, null);
            s.startRemoteListening();
            neovim.neovimChanged(new BufferChange(1, 2, List.of("beta1\r")));
            assertEquals("alpha\nbeta1\r\ngamma\n", doc.get());
            assertEquals(5, doc.getNumberOfLines(), "the typed \\r is a line break of its own");
            neovim.neovimChanged(new BufferChange(1, 2, List.of("beta1\r2")));
            assertEquals("alpha\nbeta1\r2\ngamma\n", doc.get());
            neovim.neovimChanged(new BufferChange(1, 2, List.of("beta1\r2x")));
            assertEquals("alpha\nbeta1\r2x\ngamma\n", doc.get());
            neovim.neovimChanged(new BufferChange(2, 3, List.of("GAMMA"))); // below the split line
            assertEquals("alpha\nbeta1\r2x\nGAMMA\n", doc.get());
            neovim.runQueued();
            assertEquals(List.of(Map.of(1, List.of("beta1", "2x"))), neovim.splits);
        }
    }

    /**
     * A file loaded with "beta1\r\n" (a CRLF line in an LF file): Neovim has "beta1\r", Eclipse one
     * line ending in "\r\n". No split, and a key after it keeps Neovim's text.
     */
    @Test
    void aLoadedCarriageReturnBeforeTheNewlineIsOneLine() {
        IDocument doc = new Document("alpha\nbeta1\r\ngamma\n");
        try (BufferSynchronizer s = new BufferSynchronizer(doc, neovim, UiExecutor.IMMEDIATE)) {
            s.pushLocalChanges(() -> false, null);
            s.startRemoteListening();
            neovim.neovimChanged(new BufferChange(1, 2, List.of("Beta1\r")));
            assertEquals("alpha\nBeta1\r\ngamma\n", doc.get());
            neovim.neovimChanged(new BufferChange(2, 3, List.of("GAMMA")));
            assertEquals("alpha\nBeta1\r\nGAMMA\n", doc.get());
            assertEquals(List.of(), neovim.queued, "no split: one line on both sides");
        }
    }

    /** The push at Esc of a long document writes only a region: it holds the split line. */
    @Test
    void theRegionPushedAtEscHoldsTheSplitLine() {
        IDocument doc = new Document("a\nb\nc\nd\n");
        try (BufferSynchronizer s = new BufferSynchronizer(doc, neovim, UiExecutor.IMMEDIATE)) {
            s.setCompareWholeUpToLines(0);
            s.pushLocalChanges(() -> false, null);
            s.pushDocumentToNeovim(); // the attach: from here on only the changed lines are known
            neovim.pushed.clear();
            s.startRemoteListening();
            neovim.neovimChanged(new BufferChange(1, 2, List.of("\rb")));
            s.pushChangedLinesToNeovim();
            assertEquals(List.of("1|2|[, b]"), neovim.regions, "Neovim's line 1 becomes two; c and d stay");
            neovim.neovimChanged(new BufferChange(3, 4, List.of("C"))); // Eclipse's numbers again
            assertEquals("a\n\rb\nC\nd\n", doc.get());
        }
    }

    /** A NUL byte arrives as "\0" (Neovim's API turns its internal "\n" back): no line break, no push. */
    @Test
    void aNulInsideANeovimLineIsAnOrdinaryCharacter() {
        sync.pushLocalChanges(() -> true, null);
        sync.startRemoteListening();
        neovim.neovimChanged(new BufferChange(1, 2, List.of("be\0ta")));
        assertEquals("alpha\nbe\0ta\n", document.get());
        assertEquals(List.of(), neovim.queued);
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
