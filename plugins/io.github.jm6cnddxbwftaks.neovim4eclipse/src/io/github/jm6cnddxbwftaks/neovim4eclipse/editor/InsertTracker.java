// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.BooleanSupplier;

import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InsertReplay;

/**
 * Remembers what the document looked like when an Eclipse-driven insert began to change it, so that
 * at Esc the typed text can be re-typed into Neovim (see {@link InsertReplay}) and {@code .} can
 * repeat it.
 *
 * <p>The snapshot is taken in {@code documentAboutToBeChanged} of the <em>first</em> change of an
 * insert, which gives both the text before the typing (identical to Neovim's buffer at that point)
 * and the offset where typing started, even if the caret was moved first. Changes made by the
 * plug-in itself (applying Neovim's buffer events) are excluded by the {@code eligible} check.
 *
 * <p>UI thread only.
 */
final class InsertTracker implements IDocumentListener {

    private final IDocument document;
    private final BooleanSupplier eligible;
    private String before;
    private int base = -1;

    /**
     * @param eligible whether a change right now is the user typing in a plain Eclipse-driven insert
     *                 (not a Neovim-driven edit, not Eclipse mode, not replace mode)
     */
    InsertTracker(IDocument document, BooleanSupplier eligible) {
        this.document = document;
        this.eligible = eligible;
        document.addDocumentListener(this);
    }

    void dispose() {
        document.removeDocumentListener(this);
        reset();
    }

    /** Forgets the current snapshot; the next eligible change starts a new one. */
    void reset() {
        before = null;
        base = -1;
    }

    @Override
    public void documentAboutToBeChanged(DocumentEvent event) {
        if (before == null && eligible.getAsBoolean()) {
            before = document.get();
            base = event.getOffset();
        }
    }

    @Override
    public void documentChanged(DocumentEvent event) {
        // nothing to do: only the state before the first change matters
    }

    /**
     * Builds the replay plan for the insert that is ending, and resets the tracker.
     *
     * @param caretOffset caret offset at Esc
     * @return the plan, or {@code null} if nothing was typed or it is not a replayable insertion
     */
    InsertReplay.Plan consumePlan(int caretOffset) {
        try {
            if (before == null) {
                return null;
            }
            return InsertReplay.plan(before, document.get(), base, caretOffset);
        } finally {
            reset();
        }
    }
}
