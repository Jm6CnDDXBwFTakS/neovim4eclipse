// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.BufferChange;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DocumentBuffer;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DocumentBufferPort;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.UiExecutor;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.LineRangeEdit.Edit;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.LineTextUtil;

/**
 * Keeps an Eclipse document in step with its Neovim buffer while the document is <em>not</em>
 * attached; {@link BufferSynchronizer} does it for the attached one. The editor keeps one per
 * document that has a buffer, for as long as an editor shows the document.
 *
 * <p>Both ways, so that {@code :bufdo %s/old/new/g} reaches every open file and a rename refactoring
 * reaches every buffer:
 * <ul>
 *   <li><b>Neovim to Eclipse:</b> each line event of the buffer is applied to the document on the UI
 *       thread. The editor then shows the change and is dirty; Save All (or {@code :wa}) saves it.</li>
 *   <li><b>Eclipse to Neovim:</b> every change Eclipse makes to the document is pushed to the buffer
 *       (only the lines that differ), several in a row as one push. Without it, Neovim's next edit of
 *       the buffer would be applied at stale line numbers.</li>
 * </ul>
 *
 * <h2>Attached or not</h2>
 * While the document is attached ({@link #setAttached}), its {@link BufferSynchronizer} pushes
 * Eclipse's changes (it knows when insert mode owns the typing), so this one does not. When the
 * document is detached, one push makes sure the buffer is left equal to the document: an editor
 * left in insert mode, say, has typed text Neovim has not seen. Neovim's events are routed by the
 * session, which sends only those of buffers it does not consider attached; they are always
 * applied, in order.
 *
 * <h2>Why {@code asyncExec}, not {@code syncExec}</h2>
 * {@link BufferSynchronizer} applies Neovim's changes with {@code syncExec}: the next key must see
 * the document already changed. Nothing types into a document that is not attached, so here the RPC
 * thread does not wait. Waiting would also hold up the {@code BufEnter} notifications behind the
 * change: {@code :bufdo} would then be followed into every editor instead of only the last one.
 * The UI thread's queue keeps the changes in Neovim's order.
 *
 * <h2>When both change it</h2>
 * Eclipse and Neovim changing the same document at the same moment is a race neither side can see
 * coming; the changes are applied in the order they arrive. A Neovim change that no longer fits the
 * document (it replaces lines the document does not have any more) is logged and replaced by a push
 * of Eclipse's text, so that the two agree again. A change that still fits is applied as it is.
 */
public final class UnattachedDocumentSync implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(UnattachedDocumentSync.class.getName());

    private final IDocument document;
    private final DocumentBuffer buffer;
    private final DocumentBufferPort session;
    private final UiExecutor ui;
    private final Consumer<BufferChange> neovimListener = this::onNeovimChange;
    private final IDocumentListener documentListener = new IDocumentListener() {
        @Override
        public void documentAboutToBeChanged(DocumentEvent event) {
        }

        @Override
        public void documentChanged(DocumentEvent event) {
            onEclipseChange();
        }
    };
    /** UI thread only. */
    private boolean attached;
    /** UI thread only. A change from Neovim is being written into the document: not to be pushed back. */
    private boolean applyingNeovim;
    /** A push is queued on the input thread and has not read the document yet. */
    private final AtomicBoolean pushPending = new AtomicBoolean(false);
    private volatile boolean closed;

    /** @param ui the UI thread, where the document is read and changed ({@code SwtUiExecutor} in the editor) */
    public UnattachedDocumentSync(IDocument document, DocumentBuffer buffer, DocumentBufferPort session,
            UiExecutor ui) {
        this.document = document;
        this.buffer = buffer;
        this.session = session;
        this.ui = ui;
    }

    /** Starts listening to both sides. UI thread. */
    public void start() {
        session.addUnattachedChangeListener(buffer, neovimListener);
        document.addDocumentListener(documentListener);
    }

    /**
     * Whether the document is attached now (its {@link BufferSynchronizer} pushes Eclipse's changes).
     * Becoming detached pushes the document once. UI thread.
     */
    public void setAttached(boolean attached) {
        boolean detached = this.attached && !attached;
        this.attached = attached;
        if (detached) {
            pushSoon();
        }
    }

    // ---- Neovim to Eclipse ----

    /** On the RPC thread. */
    private void onNeovimChange(BufferChange change) {
        if (closed || !ui.isAvailable()) {
            return;
        }
        ui.asyncExec(() -> apply(change));
    }

    private void apply(BufferChange change) {
        if (closed) {
            return;
        }
        if (change.lastLine() > neovimLineCount(document, FinalNewlines.of(document))) {
            // LineRangeEdit would clamp the range to the end of the document and apply it there.
            LOG.warning("Neovim's change of " + describe() + " does not fit the document (did Eclipse change it "
                    + "at the same time?); Neovim gets Eclipse's text instead: " + change);
            pushSoon();
            return;
        }
        applyingNeovim = true;
        try {
            boolean finalNewline = FinalNewlines.of(document);
            Edit edit = change.lastLine() < 0 ? BufferSynchronizer.fullBufferEdit(document, change.newLines(), finalNewline)
                    : BufferSynchronizer.rangeEdit(document, change, finalNewline);
            if (edit != null) {
                UndoSteps.beforeChange(document); // see UndoSteps: Ctrl+Z back to the save clears the marker
                document.replace(edit.offset(), edit.length(), edit.text());
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not apply Neovim's change of " + describe() + "; Neovim gets Eclipse's "
                    + "text instead: " + change, e);
            pushSoon();
        } finally {
            applyingNeovim = false;
        }
    }

    /**
     * How many lines Neovim's buffer would have for this text: Neovim has no empty line after a final
     * line break, JFace counts one. Example: {@code "a\nb\n"} is 3 JFace lines, 2 Neovim lines; but
     * 3 Neovim lines when {@code finalNewline} is false (the last one empty, see {@code LineTextUtil}).
     */
    static int neovimLineCount(IDocument document, boolean finalNewline) {
        int lines = document.getNumberOfLines();
        return finalNewline && LineTextUtil.endsWithLineBreak(document.get()) ? lines - 1 : lines;
    }

    // ---- Eclipse to Neovim ----

    /** On the UI thread (the document's listener). */
    private void onEclipseChange() {
        if (applyingNeovim || attached || closed) {
            return;
        }
        pushSoon();
    }

    /**
     * Queues one push on the input thread, unless one is queued already: it reads the document when
     * it runs, so it includes this change too (a refactoring makes many in a row).
     */
    private void pushSoon() {
        if (closed || !pushPending.compareAndSet(false, true)) {
            return;
        }
        session.runAsync("push " + describe() + " to its buffer", () -> {
            pushPending.set(false); // before reading: a change from now on queues another push
            if (closed) {
                return;
            }
            List<String> lines = documentLinesOnUi();
            if (lines == null) {
                return;
            }
            try {
                session.setDocumentBufferLines(buffer, lines);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Could not push " + describe() + " to Neovim", e);
            }
        });
    }

    /** The document's lines, read on the UI thread (see {@link BufferSynchronizer#pushDocumentToNeovim}). */
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

    private String describe() {
        return buffer.name().isEmpty() ? "buffer " + buffer.bufnr() : buffer.name();
    }

    /** Stops listening to both sides (the document's last editor closed, or Neovim was restarted). */
    @Override
    public void close() {
        closed = true;
        session.removeUnattachedChangeListener(buffer, neovimListener);
        document.removeDocumentListener(documentListener);
    }
}
