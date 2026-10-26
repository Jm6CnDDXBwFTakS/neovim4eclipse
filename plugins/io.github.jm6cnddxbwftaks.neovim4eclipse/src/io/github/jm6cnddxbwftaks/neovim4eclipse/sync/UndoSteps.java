// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.text.IDocument;
import org.eclipse.text.undo.DocumentUndoManagerRegistry;
import org.eclipse.text.undo.IDocumentUndoManager;

/**
 * Starts a new step in Eclipse's undo history before a change from Neovim is applied, as a
 * keystroke in the editor would.
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
 * <p>{@link IDocumentUndoManager#commit()} closes the open step, or, when it is empty, starts it
 * afresh, so the next change records the stamp the document has. One Neovim change is then one
 * undo step for Ctrl+Z, as one command is in Neovim. Text-model only (no SWT), so it is tested in
 * {@code tests/text}.
 */
final class UndoSteps {

    private static final Logger LOG = Logger.getLogger(UndoSteps.class.getName());

    private UndoSteps() {
    }

    /**
     * Closes the open undo step of {@code document}, if Eclipse keeps an undo history for it (an
     * open editor does). Never fails: a history that is registered but not in use (no editor has
     * connected to it, so it has no open step) throws, and the change must be applied anyway.
     * UI thread, like every change of the document.
     */
    static void beforeChange(IDocument document) {
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
