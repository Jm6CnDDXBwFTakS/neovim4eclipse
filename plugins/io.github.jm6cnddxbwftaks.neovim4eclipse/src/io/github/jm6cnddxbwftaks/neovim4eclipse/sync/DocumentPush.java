// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DocumentBuffer;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Indentation;

/**
 * Makes Neovim show an editor's document: when the editor attaches, and when Neovim mode is switched
 * back on (Eclipse may have changed the text meanwhile). The steps, in this order:
 *
 * <ol>
 *   <li>Stop applying Neovim's changes to the document (on the UI thread, now). The push below
 *       comes back from Neovim as a line event; applied, it would be a pointless rewrite, and it
 *       could deadlock with a UI thread that waits for the push (see
 *       {@link BufferSynchronizer#stopRemoteListening()}).</li>
 *   <li>On the session's input thread, after every key already queued:
 *     <ol>
 *       <li>switch Neovim to the document's own buffer. If that fails, stop: pushing now would
 *           overwrite whatever buffer Neovim is in, another file's text and undo history;</li>
 *       <li>set the tab width and indent options (a failure is only logged: Neovim then indents
 *           with its defaults);</li>
 *       <li>push the document, unless neither it nor the buffer changed since the editor was
 *           detached (its modification stamp, {@code DocumentBuffer.isInSyncAt}): an editor
 *           switch then reads neither the whole document nor the whole buffer;</li>
 *       <li>apply Neovim's changes to the document again;</li>
 *       <li>put Neovim's cursor where the caret is, or, when Neovim itself jumped into this file
 *           ({@code Ctrl-O}, a mark like {@code 'A}), move the caret to Neovim's cursor instead.
 *           A file opened again, whose buffer was kept from its closed editor, puts Neovim's cursor
 *           where it was left ({@code '"}, as Vim's {@code :e} does) and the caret follows it
 *           (docs/design/BUFFER_NUMBERS.md).</li>
 *     </ol></li>
 * </ol>
 *
 * <p><b>The caret stays put meanwhile.</b> When the caret is to be kept, Neovim's cursor reports
 * are ignored from the start until Neovim's cursor is placed ({@link Caret}). A buffer Neovim has
 * just made has its cursor on line 1, and switching to it reports that line before step 5 moves
 * the cursor to the caret. Followed, the report sent the caret to line 1 and step 5 back, and
 * Eclipse, scrolling as little as it can to show the caret again, left the caret's line at the
 * bottom of the editor. Example: a restart of Neovim with the caret on line 150 of 300, lines 140
 * to 160 on screen; afterward the screen showed 129 to 150. The reports are taken again in every
 * case, failures included, or the caret would stop following Neovim.
 *
 * <p>Whatever fails, Neovim's changes are applied again afterward: an editor that stopped listening
 * would silently stop showing what Neovim does, until it is attached again.
 *
 * <p>Neovim and the document are reached through the small {@link Neovim} and {@link Mirror}
 * interfaces, so {@code DocumentPushTest} checks the order and the failure paths with fakes.
 */
public final class DocumentPush {

    private static final Logger LOG = Logger.getLogger(DocumentPush.class.getName());

    /** The session calls the push makes. All but {@link #queue} block, on the input thread. */
    public interface Neovim {
        /** Runs {@code work} on the session's input thread, after everything queued before. */
        void queue(Runnable work);

        /** Makes the document's buffer Neovim's current buffer. */
        void showDocumentBuffer() throws Exception;

        /** Sets Neovim's {@code 'tabstop'} and indent options for the buffer. */
        void setIndentation(int tabWidth, Indentation indentation) throws Exception;

        /** Moves Neovim's cursor; {@code col0} is a 0-based UTF-8 byte column. */
        void setCursor(int line1, int col0) throws Exception;

        /** Reads Neovim's mode and cursor and passes them on, so the caret follows Neovim's cursor. */
        void takeCursorFromNeovim();

        /**
         * After the push into a buffer kept from a closed editor of the file, puts Neovim's cursor
         * where it was left ({@code '"}), and returns whether it did; {@code false} for any other
         * buffer.
         */
        default boolean goToLastPosition() throws Exception {
            return false;
        }

        /**
         * Whether the document's buffer is known to equal the document at modification stamp
         * {@code stamp} ({@code DocumentBuffer.isInSyncAt}): then the push is skipped.
         */
        default boolean bufferInSyncAt(long stamp) {
            return false;
        }
    }

    /** The editor's caret: {@code editor/CaretSync}. Both on the thread that calls them, any thread. */
    public interface Caret {
        /** Ignores Neovim's cursor reports from now on (the caret keeps its place). */
        void ignoreNeovimCursor();

        /** Takes Neovim's cursor reports again. */
        void followNeovimCursor();
    }

    /** The document side: {@link BufferSynchronizer} in the editor. */
    public interface Mirror {
        /** Stops applying Neovim's changes to the document. */
        void stopRemoteListening();

        /** Makes Neovim's buffer equal to the document. */
        void pushDocumentToNeovim();

        /** Applies Neovim's changes to the document again. */
        void startRemoteListening();

        /**
         * The document's modification stamp now, or {@code DocumentBuffer.NO_STAMP} if it has none
         * (then the push is never skipped). Called on the input thread, where the push runs; read on
         * the UI thread, as the push reads the document's lines.
         */
        default long documentStamp() {
            return DocumentBuffer.NO_STAMP;
        }
    }

    private final Neovim nvim;
    private final Mirror mirror;
    private final Caret caret;

    public DocumentPush(Neovim nvim, Mirror mirror, Caret caret) {
        this.nvim = nvim;
        this.mirror = mirror;
        this.caret = caret;
    }

    /**
     * Starts the push; returns at once (UI thread). The tab width and indentation are read by the
     * caller on the UI thread, where the widget and document may be read.
     *
     * @param line1            the caret's 1-based line, for Neovim's cursor
     * @param col0             the caret's 0-based UTF-8 byte column
     * @param takeNeovimCursor {@code true} when Neovim jumped into this file: the caret follows Neovim
     *                         instead, and {@code line1}/{@code col0} are ignored
     */
    public void start(int tabWidth, Indentation indentation, int line1, int col0, boolean takeNeovimCursor) {
        mirror.stopRemoteListening();
        if (!takeNeovimCursor) {
            caret.ignoreNeovimCursor(); // see the class comment, "The caret stays put meanwhile"
        }
        try {
            nvim.queue(() -> {
                try {
                    run(tabWidth, indentation, line1, col0, takeNeovimCursor);
                } finally {
                    if (!takeNeovimCursor) {
                        caret.followNeovimCursor();
                    }
                }
            });
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Document push could not be scheduled", e);
            mirror.startRemoteListening();
            if (!takeNeovimCursor) {
                caret.followNeovimCursor();
            }
        }
    }

    /** The input-thread part (see the class comment). */
    private void run(int tabWidth, Indentation indentation, int line1, int col0, boolean takeNeovimCursor) {
        try {
            nvim.showDocumentBuffer();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Could not switch Neovim to the document's buffer; the document was not pushed", e);
            mirror.startRemoteListening();
            return;
        }
        try {
            // Tabs as wide as in the editor (block selections are measured in screen columns), and
            // new lines indented like the code around them.
            nvim.setIndentation(tabWidth, indentation);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not set Neovim's tab and indent options", e);
        }
        try {
            // The stamp now, where the push would read the document: one read when start() was
            // called would miss an Eclipse change made while this waited in the queue.
            if (nvim.bufferInSyncAt(mirror.documentStamp())) {
                // Neither side changed since the editor was detached: the push would read the whole
                // document and buffer to find nothing to send (see DocumentBuffer.syncedStamp).
                LOG.fine("Document unchanged since its last push: not pushed again");
            } else {
                mirror.pushDocumentToNeovim();
            }
        } finally {
            mirror.startRemoteListening();
        }
        if (takeNeovimCursor) {
            nvim.takeCursorFromNeovim();
            return;
        }
        try {
            if (nvim.goToLastPosition()) {
                caret.followNeovimCursor(); // the caret goes where Neovim's cursor is now
                nvim.takeCursorFromNeovim();
                return;
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not put Neovim's cursor where the reopened file was left", e);
        }
        try {
            nvim.setCursor(line1, col0);
            LOG.fine("Preserved cursor at line=" + line1 + " col=" + col0);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to set Neovim cursor after push", e);
        }
    }
}
