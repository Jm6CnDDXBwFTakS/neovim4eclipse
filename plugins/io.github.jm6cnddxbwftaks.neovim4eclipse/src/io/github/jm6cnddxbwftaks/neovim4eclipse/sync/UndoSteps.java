// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.text.IDocument;
import org.eclipse.text.undo.DocumentUndoManagerRegistry;
import org.eclipse.text.undo.IDocumentUndoManager;

/**
 * Makes the changes of one Neovim command one step in Eclipse's undo history, as the keystroke of
 * an Eclipse command would: Ctrl+Z undoes {@code J} at once (Neovim changes one line and deletes
 * the next, two changes), and brings the document back to exactly the state before.
 *
 * <h2>Why</h2>
 * Eclipse's undo history ({@link IDocumentUndoManager}) groups document changes into steps. It
 * closes the open step when the text widget sees a key or a click (the editor's
 * {@code TextViewerUndoManager}). Keys that go to Neovim never reach the widget, so nothing closes
 * it. That matters for the "unsaved changes" marker: Eclipse clears it only when an undo brings
 * the document back to the modification stamp it was saved at, and an undo restores the stamp its
 * step recorded when it began. A step left open after an undo keeps the stamp of the change before
 * it, a stale one. Example, before this class: save at stamp 8, {@code yyp} (9), Ctrl+Z (8, clean);
 * save, {@code yyp} (10), Ctrl+Z restores 9, not 8, so the file stays marked as changed although
 * its text is the saved one.
 *
 * <h2>How</h2>
 * The first change from Neovim opens a compound step ({@link IDocumentUndoManager#beginCompoundChange},
 * which also closes whatever was open, so the step records the document's current stamp). It is
 * closed ({@link #close}) when the session says Neovim has handled the key that made the changes;
 * every change of that key has been applied by then ({@code BufferPort#addInputHandledListener}).
 * A change Neovim makes by itself, between keys (a timer in a plug-in), joins the step of the next
 * key's changes.
 *
 * <p>Text-model only (no SWT), so it is tested in {@code tests/text}. UI thread only, like every
 * change of the document.
 */
final class UndoSteps {

    private static final Logger LOG = Logger.getLogger(UndoSteps.class.getName());

    private final IDocument document;
    /** The undo manager a compound step was opened in, or {@code null} when none is open. */
    private IDocumentUndoManager open;

    UndoSteps(IDocument document) {
        this.document = document;
    }

    /** Before a change from Neovim is applied: opens the step for this key's changes, if none is open. */
    void beforeRemoteChange() {
        if (open != null) {
            return;
        }
        IDocumentUndoManager undo = DocumentUndoManagerRegistry.getDocumentUndoManager(document);
        if (undo == null) {
            return; // no editor keeps an undo history for this document
        }
        try {
            undo.beginCompoundChange();
            open = undo;
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Could not start an undo step", e); // the change is applied anyway
        }
    }

    /** Neovim has handled the key: its changes are one step. Does nothing when none is open. */
    void close() {
        IDocumentUndoManager undo = open;
        open = null;
        if (undo == null) {
            return;
        }
        try {
            undo.endCompoundChange();
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Could not end an undo step", e);
        }
    }

    /**
     * Closes the open undo step of {@code document}, or starts it afresh when empty, for a change
     * applied on its own (a document without an editor in front; see {@link UnattachedDocumentSync}).
     * Never fails: a history that is registered but not in use throws, and the change must be applied.
     */
    static void beforeChangeOnItsOwn(IDocument document) {
        IDocumentUndoManager undo = DocumentUndoManagerRegistry.getDocumentUndoManager(document);
        if (undo == null) {
            return;
        }
        try {
            undo.commit();
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Could not start a new undo step", e);
        }
    }
}
