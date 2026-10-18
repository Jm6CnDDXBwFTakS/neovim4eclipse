// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.VisualSelection;
import io.github.jm6cnddxbwftaks.eclipseneovim.sync.DocumentLineSource;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.BlockSelection;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.BlockSelection.LineRange;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.VisualSelectionGeometry;

/**
 * Shows Neovim's visual selection in the editor widget.
 *
 * <ul>
 *   <li>Characterwise ({@code v}) and linewise ({@code V}) selections become the editor's own
 *       selection, with the caret at the end where Neovim's cursor is (after {@code o} it moves to
 *       the other end). Which offsets those are is decided by {@link VisualSelectionGeometry}; the
 *       selection is set through {@link EditorCaret}.</li>
 *   <li>A blockwise selection ({@code Ctrl-V}) cannot be expressed that way: SWT's block selection
 *       works in pixels, not in Vim's screen columns (tabs, wide characters, {@code $}). The native
 *       selection is cleared, the caret is put on Neovim's cursor corner, and the rectangle is
 *       painted over the text by a {@link PaintListener}, line by line from {@link BlockSelection}.</li>
 * </ul>
 *
 * <p>A paint listener runs every time the widget repaints, after the widget has drawn its text, so
 * the overlay survives scrolling and typing; {@link #setBlockRanges} triggers a repaint when the
 * rectangle changes. UI thread only.
 */
final class VisualSelectionView {

    private static final Logger LOG = Logger.getLogger(VisualSelectionView.class.getName());

    /** Opacity of the blockwise-selection overlay (0-255). */
    private static final int BLOCK_ALPHA = 110;

    private final StyledText styledText;
    private final WidgetOffsets offsets;
    private final EditorCaret caret;
    /** Runs caret changes so that the controller does not echo them back to Neovim as user moves. */
    private final Consumer<Runnable> asCaretFromNeovim;
    private IDocument document;
    /** Rectangle of a blockwise visual selection, one range per line. */
    private List<LineRange> blockRanges = List.of();
    private final PaintListener blockPainter = this::paintBlockSelection;

    /**
     * @param asCaretFromNeovim runs the given caret change marked as coming from Neovim
     */
    VisualSelectionView(StyledText styledText, WidgetOffsets offsets, EditorCaret caret,
            Consumer<Runnable> asCaretFromNeovim) {
        this.styledText = styledText;
        this.offsets = offsets;
        this.caret = caret;
        this.asCaretFromNeovim = asCaretFromNeovim;
        styledText.addPaintListener(blockPainter);
    }

    /** Shows {@code sel} (or clears the selection when it is not active). */
    void show(IDocument doc, VisualSelection sel) {
        document = doc;
        if (!sel.active()) {
            setBlockRanges(List.of());
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
        setBlockRanges(List.of());
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
        setBlockRanges(ranges);
    }

    /** Removes a blockwise overlay (Eclipse mode has no Vim selection). */
    void clearBlock() {
        setBlockRanges(List.of());
    }

    private void setBlockRanges(List<LineRange> ranges) {
        boolean changed = !blockRanges.equals(ranges);
        blockRanges = ranges;
        if (changed && !styledText.isDisposed()) {
            styledText.redraw();
        }
    }

    /** Draws the block overlay after the editor has painted the text. Only visible lines are measured. */
    private void paintBlockSelection(PaintEvent event) {
        List<LineRange> ranges = blockRanges;
        IDocument doc = document;
        if (ranges.isEmpty() || doc == null || styledText.isDisposed()) {
            return;
        }
        GC gc = event.gc;
        Color oldBackground = gc.getBackground();
        int oldAlpha = gc.getAlpha();
        try {
            int lineHeight = Math.max(1, styledText.getLineHeight());
            int firstVisible = styledText.getTopIndex();
            int lastVisible = firstVisible + styledText.getSize().y / lineHeight + 1;
            gc.setBackground(styledText.getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION));
            gc.setAlpha(BLOCK_ALPHA);
            for (LineRange range : ranges) {
                if (range.line0() >= doc.getNumberOfLines()) {
                    continue;
                }
                // Ranges are in document lines; folded lines have no widget offset and are skipped.
                int lineOffset = doc.getLineOffset(range.line0());
                int fromOffset = offsets.toWidget(lineOffset + range.startChar());
                int toOffset = offsets.toWidget(lineOffset + range.endChar());
                if (fromOffset < 0 || toOffset < 0) {
                    continue;
                }
                int widgetLine = styledText.getLineAtOffset(fromOffset);
                if (widgetLine < firstVisible || widgetLine > lastVisible) {
                    continue;
                }
                Point from = styledText.getLocationAtOffset(fromOffset);
                Point to = styledText.getLocationAtOffset(toOffset);
                if (to.x > from.x) {
                    gc.fillRectangle(from.x, from.y, to.x - from.x, lineHeight);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "block overlay paint failed", e); // e.g. document changed under us
        } finally {
            gc.setAlpha(oldAlpha);
            gc.setBackground(oldBackground);
        }
    }

    /** Removes the overlay and the paint listener. */
    void dispose() {
        blockRanges = List.of();
        if (!styledText.isDisposed()) {
            styledText.removePaintListener(blockPainter);
            styledText.redraw();
        }
    }
}
