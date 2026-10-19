// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.util.List;
import java.util.function.Consumer;

import org.eclipse.jface.text.IDocument;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.VisualSelection;
import io.github.jm6cnddxbwftaks.eclipseneovim.sync.DocumentLineSource;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.BlockSelection;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.BlockSelection.LineRange;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.VisualSelectionGeometry;

/**
 * Shows Neovim's visual selection in the editor.
 *
 * <ul>
 *   <li>Characterwise ({@code v}) and linewise ({@code V}) selections become the editor's own
 *       selection, with the caret at the end where Neovim's cursor is (after {@code o} it moves to
 *       the other end). Which offsets those are is decided by {@link VisualSelectionGeometry}; the
 *       selection is set through {@link EditorCaret}.</li>
 *   <li>A blockwise selection ({@code Ctrl-V}) cannot be an editor selection: the native selection
 *       is cleared, the caret is put on Neovim's cursor corner, and a {@link BlockHighlight}
 *       ({@link BlockOverlay} in the editor) shows the rectangle that {@link BlockSelection} computes.</li>
 * </ul>
 *
 * <p>Decides only; the widget work is behind {@link EditorCaret} and {@link BlockHighlight}, so it is
 * tested without SWT ({@code tests/text}). UI thread only.
 */
final class VisualSelectionView {

    private final EditorCaret caret;
    private final BlockHighlight overlay;
    /** Runs caret changes so that the controller does not echo them back to Neovim as user moves. */
    private final Consumer<Runnable> asCaretFromNeovim;

    /**
     * @param asCaretFromNeovim runs the given caret change marked as coming from Neovim
     */
    VisualSelectionView(EditorCaret caret, BlockHighlight overlay, Consumer<Runnable> asCaretFromNeovim) {
        this.caret = caret;
        this.overlay = overlay;
        this.asCaretFromNeovim = asCaretFromNeovim;
    }

    /** Shows {@code sel} (or clears the selection when it is not active). */
    void show(IDocument doc, VisualSelection sel) {
        if (!sel.active()) {
            overlay.show(doc, List.of());
            if (caret.selectionLength() > 0) {
                int at = caret.caretOffset();
                caret.select(at, at);
            }
            return;
        }
        if (sel.isBlockWise()) {
            showBlock(doc, sel);
            return;
        }
        overlay.show(doc, List.of());
        // Neovim reports the anchor ('v') first and the cursor second.
        VisualSelectionGeometry.Range range = VisualSelectionGeometry.of(new DocumentLineSource(doc),
                sel.startLine1Based(), sel.startCol0Based(), sel.endLine1Based(), sel.endCol0Based(),
                sel.isLineWise());
        // Marked as coming from Neovim: otherwise the caret listener would send the caret back and
        // move Neovim's cursor.
        asCaretFromNeovim.accept(() -> caret.select(range.anchor(), range.caret()));
    }

    private void showBlock(IDocument doc, VisualSelection sel) {
        List<LineRange> ranges = BlockSelection.compute(new DocumentPositions.DocumentLines(doc),
                sel.startLine1Based(), sel.startCol0Based(), sel.endLine1Based(), sel.endCol0Based(),
                sel.toEndOfLine(), sel.tabstop());
        // The caret goes on Neovim's cursor corner; the native selection is cleared.
        int cursor = VisualSelectionGeometry.offsetOf(new DocumentLineSource(doc), sel.endLine1Based(),
                sel.endCol0Based());
        asCaretFromNeovim.accept(() -> caret.select(cursor, cursor));
        overlay.show(doc, ranges);
    }

    /** Removes a blockwise overlay (Eclipse mode has no Vim selection). */
    void clearBlock() {
        overlay.clear();
    }

    /** Removes the overlay and its paint listener. */
    void dispose() {
        overlay.dispose();
    }
}
