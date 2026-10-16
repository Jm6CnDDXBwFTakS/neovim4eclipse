// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.sync;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.swt.widgets.Display;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.NeovimSession;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.NeovimSession.BufferChange;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.LineRangeEdit;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.LineRangeEdit.Edit;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.LineRangeEdit.LineSource;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.LineTextUtil;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.TextDiff;

/**
 * Keeps one Eclipse {@link IDocument} in sync with the Neovim buffer.
 *
 * <p>Direction of flow:
 * <ul>
 *   <li><b>Eclipse to Neovim</b>: only whole-document pushes ({@link #pushDocumentToNeovim()}),
 *       on attach, when Neovim mode is re-enabled, and when leaving insert mode. While in insert
 *       mode Eclipse owns typing and Neovim learns about it at the push. A push changes only the
 *       lines that differ, so the buffer's undo history and marks survive it.</li>
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
public final class BufferSynchronizer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(BufferSynchronizer.class.getName());

    private final IDocument document;
    private final NeovimSession session;
    private final AtomicBoolean applyingRemote = new AtomicBoolean(false);
    private final Consumer<BufferChange> bufferChangeListener = this::onNeovimBufferChange;

    private volatile boolean listening;
    private volatile boolean closed;
    /** Called on the UI thread after each remote mutation, to put the caret back where Neovim has it. */
    private volatile Runnable caretRestorer;

    public BufferSynchronizer(IDocument document, NeovimSession session) {
        this.document = document;
        this.session = session;
    }

    public void setCaretRestorer(Runnable caretRestorer) {
        this.caretRestorer = caretRestorer;
    }

    /** @return {@code true} while a Neovim-originated change is being written into the document */
    public boolean isApplyingRemote() {
        return applyingRemote.get();
    }

    /** Starts applying Neovim to Eclipse changes. Call only after the initial push. */
    public void startRemoteListening() {
        if (listening || closed) {
            return;
        }
        listening = true;
        session.addBufferChangeListener(bufferChangeListener);
        LOG.fine("BufferSynchronizer: remote listening enabled");
    }

    /**
     * Stops applying Neovim changes to Eclipse. Call before {@link #pushDocumentToNeovim()}
     * when re-syncing an already attached editor, then call {@link #startRemoteListening()} again.
     *
     * <p>Why: Neovim echoes a push back as a line event, which is applied with {@code syncExec}
     * on the UI thread. If the UI thread is itself waiting for the push, that deadlocks.
     */
    public void stopRemoteListening() {
        listening = false;
        session.removeBufferChangeListener(bufferChangeListener);
    }

    /**
     * Makes the Neovim buffer equal to the Eclipse document, changing only the lines that differ.
     * Blocking RPC: not for the UI thread.
     */
    public void pushDocumentToNeovim() {
        if (closed) {
            return;
        }
        try {
            List<String> lines = documentToLines(document);
            session.setBufferLines(lines);
            LOG.fine(() -> "Pushed " + lines.size() + " lines to Neovim");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to push document to Neovim", e);
        }
    }

    /**
     * Eclipse document to Neovim lines. The empty "line" after a final line break is not a
     * Neovim line; the final line break itself is preserved by later edits (see {@link LineTextUtil}).
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
        try {
            Edit edit = change.lastLine() < 0 ? fullBufferEdit(change.newLines()) : rangeEdit(change);
            if (edit != null) {
                document.replace(edit.offset(), edit.length(), edit.text());
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error applying Neovim change " + change, e);
        } finally {
            applyingRemote.set(false);
            restoreCaret();
        }
        if (change.afterApply() != null) {
            change.afterApply().run(); // e.g. the session lets Eclipse type once insert mode's sync has landed
        }
    }

    private Edit fullBufferEdit(List<String> newLines) {
        String current = document.get();
        String text = LineTextUtil.linesToDocumentText(newLines, lineDelimiter(document),
                LineTextUtil.endsWithLineBreak(current)); // keep the document's final line break as it is
        return TextDiff.minimalEdit(current, text); // null when nothing changed: no spurious dirty flag
    }

    private Edit rangeEdit(BufferChange change) {
        return LineRangeEdit.compute(new DocumentLines(document), change.firstLine(), change.lastLine(),
                change.newLines(), lineDelimiter(document));
    }

    /** {@link LineSource} view of an {@link IDocument}. */
    private static final class DocumentLines implements LineSource {
        private final IDocument doc;

        DocumentLines(IDocument doc) {
            this.doc = doc;
        }

        @Override
        public int lineCount() {
            return doc.getNumberOfLines();
        }

        @Override
        public int lineOffset(int line) {
            try {
                return doc.getLineOffset(line);
            } catch (BadLocationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public int lineContentLength(int line) {
            try {
                return doc.getLineInformation(line).getLength();
            } catch (BadLocationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public int length() {
            return doc.getLength();
        }

        @Override
        public String text(int offset, int length) {
            try {
                return doc.get(offset, length);
            } catch (BadLocationException e) {
                throw new IllegalStateException(e);
            }
        }
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

    private static String lineDelimiter(IDocument document) {
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
    private void runOnUi(Runnable r) {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        if (display.getThread() == Thread.currentThread()) {
            r.run();
        } else {
            display.syncExec(() -> {
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
    }
}
