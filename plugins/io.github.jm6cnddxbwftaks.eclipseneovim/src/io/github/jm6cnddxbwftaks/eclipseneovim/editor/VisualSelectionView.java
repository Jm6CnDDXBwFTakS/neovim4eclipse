// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.VisualSelection;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.BlockSelection;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.BlockSelection.LineRange;

/**
 * Shows Neovim's visual selection in the editor widget.
 *
 * <ul>
 *   <li>Characterwise ({@code v}) and linewise ({@code V}) selections become the widget's own
 *       selection ({@link StyledText#setSelection(int, int)}), with the caret at the end where
 *       Neovim's cursor is (after {@code o} it moves to the other end).</li>
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
    /** Runs caret changes so that the controller does not echo them back to Neovim as user moves. */
    private final Consumer<Runnable> asCaretFromNeovim;
    private IDocument document;
    /** Rectangle of a blockwise visual selection, one range per line. */
    private List<LineRange> blockRanges = List.of();
    private final PaintListener blockPainter = this::paintBlockSelection;

    /**
     * @param asCaretFromNeovim runs the given caret change marked as coming from Neovim
     */
    VisualSelectionView(StyledText styledText, WidgetOffsets offsets, Consumer<Runnable> asCaretFromNeovim) {
        this.styledText = styledText;
        this.offsets = offsets;
        this.asCaretFromNeovim = asCaretFromNeovim;
        styledText.addPaintListener(blockPainter);
    }

    /** Shows {@code sel} (or clears the selection when it is not active). */
    void show(IDocument doc, VisualSelection sel) throws BadLocationException {
        document = doc;
        if (!sel.active()) {
            setBlockRanges(List.of());
            int caret = styledText.getCaretOffset();
            if (styledText.getSelectionCount() > 0) {
                styledText.setSelection(caret, caret);
            }
            return;
        }
        if (sel.isBlockWise()) {
            showBlock(doc, sel);
            return;
        }
        setBlockRanges(List.of());
        // Neovim reports the anchor ('v') first and the cursor second, so after "V" then "k" the
        // start is below the end. Order the corners before converting: a linewise start becomes the
        // beginning of its line and the end the end of its line, which only works top to bottom.
        boolean anchorFirst = sel.startLine1Based() < sel.endLine1Based()
                || (sel.startLine1Based() == sel.endLine1Based() && sel.startCol0Based() <= sel.endCol0Based());
        int topLine = anchorFirst ? sel.startLine1Based() : sel.endLine1Based();
        int topCol = anchorFirst ? sel.startCol0Based() : sel.endCol0Based();
        int bottomLine = anchorFirst ? sel.endLine1Based() : sel.startLine1Based();
        int bottomCol = anchorFirst ? sel.endCol0Based() : sel.startCol0Based();
        int start = DocumentPositions.visualToOffset(doc, topLine, topCol, sel.isLineWise(), true);
        int end = DocumentPositions.visualToOffset(doc, bottomLine, bottomCol, sel.isLineWise(), false);
        // Neovim's visual selection includes the character under the cursor; SWT's end is exclusive.
        if (!sel.isLineWise() && end < doc.getLength()) {
            end++;
        }
        // Document offsets; the widget leaves folded code out, so convert (unfolding what is selected).
        int widgetStart = offsets.toWidgetRevealing(start);
        int widgetEnd = offsets.toWidgetRevealing(end);
        if (widgetStart < 0 || widgetEnd < 0) {
            return;
        }
        // The caret goes to the end Neovim's cursor is on, so "o" visibly jumps to the other end.
        // SWT puts the caret at the first argument when it is the larger one. Marked as coming
        // from Neovim: otherwise the caret listener would send the caret back and move the cursor.
        boolean cursorOnTop = !anchorFirst;
        asCaretFromNeovim.accept(() -> {
            if (cursorOnTop) {
                styledText.setSelection(widgetEnd, widgetStart);
            } else {
                styledText.setSelection(widgetStart, widgetEnd);
            }
            styledText.showSelection();
        });
    }

    private void showBlock(IDocument doc, VisualSelection sel) throws BadLocationException {
        List<LineRange> ranges = BlockSelection.compute(new DocumentPositions.DocumentLines(doc),
                sel.startLine1Based(), sel.startCol0Based(), sel.endLine1Based(), sel.endCol0Based(),
                sel.toEndOfLine(), sel.tabstop());
        int caret = offsets.toWidgetRevealing(Math.min(
                DocumentPositions.byteColToOffset(doc, sel.endLine1Based(), sel.endCol0Based()), doc.getLength()));
        asCaretFromNeovim.accept(() -> {
            if (caret >= 0) {
                styledText.setSelection(caret); // clears any native selection and places the caret
                styledText.showSelection();
            }
        });
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
