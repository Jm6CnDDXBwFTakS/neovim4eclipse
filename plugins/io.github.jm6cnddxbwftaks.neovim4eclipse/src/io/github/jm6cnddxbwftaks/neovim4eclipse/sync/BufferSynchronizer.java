// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.IDocumentListener;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.BufferPort;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.UiExecutor;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.BufferChange;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DocumentBuffer;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.LineRangeEdit;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.LineRangeEdit.Edit;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.LineTextUtil;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TextDiff;

/**
 * Keeps one Eclipse {@link IDocument} in sync with the Neovim buffer.
 *
 * <p>Direction of flow:
 * <ul>
 *   <li><b>Eclipse to Neovim</b>: only whole-document pushes ({@link #pushDocumentToNeovim()}),
 *       on attach, when Neovim mode is re-enabled, when leaving insert mode, and after a change
 *       Eclipse makes by itself outside insert mode ({@link #pushLocalChanges}: a format, a
 *       refactoring, Eclipse's undo, a revert). While in insert mode Eclipse owns typing and Neovim
 *       learns about it at the push. A push changes only the lines that differ, so the buffer's
 *       undo history and marks survive it.</li>
 *   <li><b>Neovim to Eclipse</b>: line-range events from {@code nvim_buf_lines_event}, applied as
 *       minimal edits on the UI thread. A full buffer ({@code lastLine == -1}) is applied as the
 *       smallest edit that makes the texts equal.</li>
 * </ul>
 *
 * <p><b>Ordering:</b> call {@link #pushDocumentToNeovim()} <em>before</em>
 * {@link #startRemoteListening()} on attach, so that the echo of the initial {@code set_lines}
 * cannot rewrite the Eclipse document.
 *
 * <p>All document mutation happens on the UI thread; {@link #isApplyingRemote()} lets UI listeners
 * tell a Neovim-driven change (caret moves, etc.) from a user-driven one.
 */
public final class BufferSynchronizer implements AutoCloseable, DocumentPush.Mirror {

    private static final Logger LOG = Logger.getLogger(BufferSynchronizer.class.getName());

    private final IDocument document;
    private final BufferPort session;
    private final UiExecutor ui;
    private final AtomicBoolean applyingRemote = new AtomicBoolean(false);
    /** Document changes seen while one Neovim change was applied (more than 1: see {@link #applyChange}). */
    private int changesWhileApplying;
    /**
     * Eclipse changed the document in reaction to a Neovim change, and Neovim has not been sent
     * the result yet. Until then, Neovim's full-text syncs are out of date and are not applied.
     */
    private volatile boolean neovimBehind;
    private final Consumer<BufferChange> bufferChangeListener = this::onNeovimBufferChange;
    /** Groups the changes of one Neovim command into one Eclipse undo step. UI thread. */
    private final UndoSteps undoSteps;
    /** Neovim has handled a key: the undo step of its changes ends (on the UI thread, after them). */
    private final Runnable inputHandledListener = this::closeUndoStepSoon;

    private volatile boolean listening;
    private volatile boolean closed;
    /** Called on the UI thread after each remote mutation, to put the caret back where Neovim has it. */
    private volatile Runnable caretRestorer;
    /** When Eclipse's own document changes are pushed (see {@link #pushLocalChanges}); {@code null}: never. */
    private volatile BooleanSupplier localPushGate;
    /** Runs on the session's input thread after each such push (see {@link #pushLocalChanges}). */
    private volatile Runnable afterLocalPush;
    /** A push of Eclipse's own changes is queued and has not read the document yet. */
    private final AtomicBoolean localPushPending = new AtomicBoolean(false);
    private final IDocumentListener localChangeListener = new IDocumentListener() {
        @Override
        public void documentAboutToBeChanged(DocumentEvent event) {
        }

        @Override
        public void documentChanged(DocumentEvent event) {
            if (applyingRemote.get()) {
                changesWhileApplying++;
                return;
            }
            onLocalChange();
        }
    };

    /** @param ui the UI thread, where the document is changed ({@code SwtUiExecutor} in the editor) */
    public BufferSynchronizer(IDocument document, BufferPort session, UiExecutor ui) {
        this.document = document;
        this.session = session;
        this.ui = ui;
        this.undoSteps = new UndoSteps(document);
    }

    public void setCaretRestorer(Runnable caretRestorer) {
        this.caretRestorer = caretRestorer;
    }

    /** @return {@code true} while a Neovim-originated change is being written into the document */
    public boolean isApplyingRemote() {
        return applyingRemote.get();
    }

    /**
     * Pushes every change Eclipse makes to the document by itself to Neovim, while {@code when} is
     * true. Without this, a format (Ctrl+Shift+F) or a refactoring in normal mode would leave
     * Neovim's buffer behind, and Neovim's next edit would be applied at stale line numbers.
     *
     * <p>Changes applied from Neovim are not pushed back ({@link #isApplyingRemote()}). Several
     * changes in a row (a refactoring makes many) are coalesced: one push is queued on the session's
     * input thread, and it reads the document when it runs. Being queued there also orders it
     * before any key typed afterward.
     *
     * <p>After the push, {@code afterPush} runs on the input thread. The editor uses it to move
     * Neovim's cursor to Eclipse's caret: Eclipse's undo puts the caret on the line it brings back,
     * but inserting that line above Neovim's cursor moves the cursor one line down.
     *
     * @param when      evaluated on the UI thread at each change; the editor passes "Neovim mode is on
     *                  and Neovim is not in insert mode" (typing in insert mode is pushed when it ends)
     * @param afterPush run on the session's input thread after each push; may block on RPC
     */
    public void pushLocalChanges(BooleanSupplier when, Runnable afterPush) {
        localPushGate = when;
        afterLocalPush = afterPush;
        document.addDocumentListener(localChangeListener);
    }

    private void onLocalChange() {
        BooleanSupplier gate = localPushGate;
        if (closed || gate == null || !gate.getAsBoolean()) {
            return;
        }
        if (!localPushPending.compareAndSet(false, true)) {
            return; // the queued push has not read the document yet: it will include this change
        }
        session.runAsync("push Eclipse's change", () -> {
            localPushPending.set(false); // before reading: a change from now on queues another push
            if (closed) {
                return;
            }
            // Neovim echoes the push as line events; applying them could undo a newer Eclipse change.
            boolean wasListening = listening;
            stopRemoteListening();
            try {
                pushDocumentToNeovim();
            } finally {
                if (wasListening) {
                    startRemoteListening();
                }
            }
            Runnable after = afterLocalPush;
            if (after != null && !closed) {
                try {
                    after.run();
                } catch (Exception e) {
                    LOG.log(Level.FINE, "after-push action failed", e);
                }
            }
        });
    }

    /** Starts applying Neovim to Eclipse changes. Call only after the initial push. */
    @Override
    public void startRemoteListening() {
        if (listening || closed) {
            return;
        }
        listening = true;
        session.addBufferChangeListener(bufferChangeListener);
        session.addInputHandledListener(inputHandledListener);
        LOG.fine("BufferSynchronizer: remote listening enabled");
    }

    /**
     * Stops applying Neovim changes to Eclipse. Call before {@link #pushDocumentToNeovim()}
     * when re-syncing an already attached editor, then call {@link #startRemoteListening()} again.
     *
     * <p>Why: Neovim echoes a push back as a line event, which is applied with {@code syncExec}
     * on the UI thread. If the UI thread is itself waiting for the push, that deadlocks.
     */
    @Override
    public void stopRemoteListening() {
        listening = false;
        session.removeBufferChangeListener(bufferChangeListener);
        session.removeInputHandledListener(inputHandledListener);
        closeUndoStepSoon(); // no more changes from Neovim for now: their step ends
    }

    /**
     * Makes the Neovim buffer equal to the Eclipse document, changing only the lines that differ.
     * Blocking RPC: not for the UI thread.
     *
     * <p>The document's text is read on the UI thread ({@link #documentLinesOnUi()}), the buffer
     * written here. A JFace document is not thread-safe: read from this thread while the UI thread
     * edits it (linked mode, a formatter, save actions), it could return torn text or throw.
     */
    @Override
    public void pushDocumentToNeovim() {
        if (closed) {
            return;
        }
        try {
            List<String> lines = documentLinesOnUi();
            if (lines == null) {
                return; // the UI is gone or the editor closed meanwhile
            }
            session.setBufferLines(lines);
            neovimBehind = false;
            LOG.fine(() -> "Pushed " + lines.size() + " lines to Neovim");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to push document to Neovim", e);
        }
    }

    /**
     * A snapshot of the document's lines, taken on the UI thread, between two of its events, so never
     * in the middle of an edit. Waits for the UI thread when called from another one; the UI thread
     * never waits for the session's threads, so this cannot deadlock.
     *
     * @return the lines, or {@code null} if there is no UI any more or the synchronizer was closed
     */
    private List<String> documentLinesOnUi() {
        if (ui.isUiThread()) {
            return FinalNewlines.linesToPush(document);
        }
        if (!ui.isAvailable()) {
            return null;
        }
        List<List<String>> snapshot = new ArrayList<>(1);
        ui.syncExec(() -> {
            if (!closed) {
                snapshot.add(FinalNewlines.linesToPush(document));
            }
        });
        return snapshot.isEmpty() ? null : snapshot.get(0);
    }

    /**
     * Eclipse document to Neovim lines. The empty "line" after a final line break is not a
     * Neovim line, and a final newline is assumed. For reading a document's lines (its indentation);
     * a push uses {@link FinalNewlines#linesToPush}, which knows the document's final newline.
     */
    public static List<String> documentToLines(IDocument document) {
        return LineTextUtil.splitDocumentLines(document.get());
    }

    // ---- Neovim to Eclipse ----

    private void onNeovimBufferChange(BufferChange change) {
        if (closed || !listening) {
            return;
        }
        runOnUi(() -> applyChange(change));
    }

    private void applyChange(BufferChange change) {
        if (!listening) {
            return;
        }
        applyingRemote.set(true);
        changesWhileApplying = 0;
        try {
            boolean fullBuffer = change.lastLine() < 0;
            boolean newline = FinalNewlines.of(document);
            Edit edit = fullBuffer && neovimBehind ? null
                    : fullBuffer ? fullBufferEdit(document, change.newLines(), newline)
                    : rangeEdit(document, change, newline);
            if (edit != null) {
                undoSteps.beforeRemoteChange(); // one undo step per Neovim command (see UndoSteps)
                document.replace(edit.offset(), edit.length(), edit.text());
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error applying Neovim change " + change, e);
        } finally {
            applyingRemote.set(false);
            restoreCaret();
        }
        // More than one change: Eclipse reacted to Neovim's edit with edits of its own, inside the
        // same replace. In a linked rename (Alt+Shift+R), "x" on the name is copied to every other
        // occurrence; Neovim must get those copies, or its next edit would hit stale text. The
        // full-text sync Neovim sends after the key would also undo them, so those are skipped
        // until the push (in insert mode, the one when insert mode ends).
        if (changesWhileApplying > 1) {
            neovimBehind = true;
            onLocalChange();
        }
        if (change.afterApply() != null) {
            change.afterApply().run(); // e.g. the session lets Eclipse type once insert mode's sync has landed
        }
    }

    /**
     * The edit that makes {@code document} read {@code newLines} (Neovim's whole buffer), or
     * {@code null} when it already does. Also used by {@link UnattachedDocumentSync}.
     *
     * @param finalNewline whether the text ends with a final newline after the last line, as
     *                     remembered for the document ({@link FinalNewlines})
     */
    static Edit fullBufferEdit(IDocument document, List<String> newLines, boolean finalNewline) {
        String current = document.get();
        String text = LineTextUtil.linesToDocumentText(newLines, lineDelimiter(document), finalNewline);
        return TextDiff.minimalEdit(current, text); // null when nothing changed: no spurious dirty flag
    }

    /** The edit that applies Neovim's line-range change to {@code document}; see {@link #fullBufferEdit}. */
    static Edit rangeEdit(IDocument document, BufferChange change, boolean finalNewline) {
        return LineRangeEdit.compute(new DocumentLineSource(document), change.firstLine(), change.lastLine(),
                change.newLines(), lineDelimiter(document), finalNewline);
    }

    private void restoreCaret() {
        Runnable restorer = caretRestorer;
        if (restorer != null && !closed) {
            try {
                restorer.run();
            } catch (Exception e) {
                LOG.log(Level.FINE, "caretRestorer failed", e);
            }
        }
    }

    @Override
    public long documentStamp() {
        return stampOf(document);
    }

    /** {@code document}'s modification stamp, or {@code DocumentBuffer.NO_STAMP} if it keeps none. */
    static long stampOf(IDocument document) {
        if (document instanceof IDocumentExtension4 ext4) {
            long stamp = ext4.getModificationStamp();
            return stamp == IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP ? DocumentBuffer.NO_STAMP : stamp;
        }
        return DocumentBuffer.NO_STAMP;
    }

    static String lineDelimiter(IDocument document) {
        if (document instanceof IDocumentExtension4 ext4) {
            String delim = ext4.getDefaultLineDelimiter();
            if (delim != null && !delim.isEmpty()) {
                return delim;
            }
        }
        try {
            if (document.getNumberOfLines() > 0) {
                String delim = document.getLineDelimiter(0);
                if (delim != null && !delim.isEmpty()) {
                    return delim;
                }
            }
        } catch (BadLocationException ignored) {
            // fall through to the platform default
        }
        return LineTextUtil.platformLineDelimiter();
    }

    /**
     * Runs on the UI thread. From other threads this uses {@code syncExec} on purpose: the
     * document must reflect a change before the next key is processed, otherwise a following
     * range event would be applied against stale line numbers.
     */
    /**
     * Ends the undo step of Neovim's latest changes, on the UI thread, without waiting for it: a key
     * must not wait for the UI. Changes applied before (with {@code syncExec}) are in the step; the
     * UI thread runs this after them.
     */
    private void closeUndoStepSoon() {
        if (ui.isUiThread()) {
            undoSteps.close();
        } else if (ui.isAvailable()) {
            ui.asyncExec(undoSteps::close);
        }
    }

    private void runOnUi(Runnable r) {
        if (!ui.isAvailable()) {
            return;
        }
        if (ui.isUiThread()) {
            r.run();
        } else {
            ui.syncExec(() -> {
                if (!closed) {
                    r.run();
                }
            });
        }
    }

    @Override
    public void close() {
        closed = true;
        listening = false;
        session.removeBufferChangeListener(bufferChangeListener);
        session.removeInputHandledListener(inputHandledListener);
        document.removeDocumentListener(localChangeListener);
        closeUndoStepSoon();
    }
}
