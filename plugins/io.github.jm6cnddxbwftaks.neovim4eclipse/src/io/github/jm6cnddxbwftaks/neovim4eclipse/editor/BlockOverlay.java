// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.BlockSelection;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.BlockSelection.LineRange;

/**
 * Paints a blockwise visual selection ({@code Ctrl-V}) over the editor's text.
 *
 * <p>Why paint: SWT's own block selection works in pixels, not in Vim's screen columns (tabs, wide
 * characters, {@code $}), so the native selection cannot show what Neovim selected. The covered
 * characters of each line come from {@link BlockSelection}; this class only draws them, as
 * semi-transparent rectangles.
 *
 * <p>A {@link PaintListener} runs every time the widget repaints, after it has drawn its text, so the
 * overlay survives scrolling and typing; {@link #show} repaints when the rectangle changes. Only
 * visible lines are measured; folded lines are skipped. UI thread only.
 *
 * <p>The editor's {@link BlockHighlight}; tests of the deciding code use a fake instead.
 */
final class BlockOverlay implements BlockHighlight {

    private static final Logger LOG = Logger.getLogger(BlockOverlay.class.getName());

    /** Opacity of the overlay (0-255): the text under it stays legible. */
    private static final int ALPHA = 110;

    private final StyledText styledText;
    private final WidgetOffsets offsets;
    private final PaintListener painter = event -> paint(event.gc);
    private IDocument document;
    /** One range per line of the block, in document lines; empty when there is no block. */
    private List<LineRange> ranges = List.of();

    BlockOverlay(StyledText styledText, WidgetOffsets offsets) {
        this.styledText = styledText;
        this.offsets = offsets;
        styledText.addPaintListener(painter);
    }

    /** Shows the block {@code lineRanges} of {@code doc}; an empty list removes the overlay. */
    @Override
    public void show(IDocument doc, List<LineRange> lineRanges) {
        document = doc;
        boolean changed = !ranges.equals(lineRanges);
        ranges = List.copyOf(lineRanges);
        if (changed && !styledText.isDisposed()) {
            styledText.redraw();
        }
    }

    /** Removes the overlay. */
    @Override
    public void clear() {
        show(document, List.of());
    }

    /** The ranges being shown; for tests. */
    List<LineRange> ranges() {
        return ranges;
    }

    /**
     * Draws the rectangles with {@code gc}. Called by the paint listener after the widget has drawn
     * its text; package-private so a test can paint into an image.
     */
    void paint(GC gc) {
        List<LineRange> lineRanges = ranges;
        IDocument doc = document;
        if (lineRanges.isEmpty() || doc == null || styledText.isDisposed()) {
            return;
        }
        Color oldBackground = gc.getBackground();
        int oldAlpha = gc.getAlpha();
        try {
            int lineHeight = Math.max(1, styledText.getLineHeight());
            int firstVisible = styledText.getTopIndex();
            int lastVisible = firstVisible + styledText.getSize().y / lineHeight + 1;
            gc.setBackground(styledText.getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION));
            gc.setAlpha(ALPHA);
            for (LineRange range : lineRanges) {
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
            LOG.log(Level.FINE, "block overlay paint failed", e); // e.g. the document changed under us
        } finally {
            gc.setAlpha(oldAlpha);
            gc.setBackground(oldBackground);
        }
    }

    /** Removes the overlay and the paint listener. */
    @Override
    public void dispose() {
        ranges = List.of();
        if (!styledText.isDisposed()) {
            styledText.removePaintListener(painter);
            styledText.redraw();
        }
    }
}
