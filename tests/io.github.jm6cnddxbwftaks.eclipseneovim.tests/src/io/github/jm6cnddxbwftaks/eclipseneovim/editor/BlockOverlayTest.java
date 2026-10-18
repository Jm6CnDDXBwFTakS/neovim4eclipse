// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.BlockSelection.LineRange;

/**
 * {@link BlockOverlay} on a real widget. The painting test draws into an image instead of the
 * screen, so it does not depend on the window being visible: a pixel inside the block must be
 * shaded, one outside must stay the background.
 */
class BlockOverlayTest {

    private static final String TEXT = "alpha\nbeta\ngamma";

    private Shell shell;
    private StyledText widget;
    private BlockOverlay overlay;
    private final IDocument doc = new Document(TEXT);

    @BeforeEach
    void create() {
        shell = new Shell(Display.getCurrent());
        widget = new StyledText(shell, SWT.MULTI);
        widget.setText(TEXT);
        widget.setSize(400, 200);
        overlay = new BlockOverlay(widget, new WidgetOffsets(widget, null));
    }

    @AfterEach
    void dispose() {
        shell.dispose();
    }

    @Test
    void showKeepsTheRangesAndClearRemovesThem() {
        List<LineRange> block = List.of(new LineRange(0, 1, 3), new LineRange(1, 1, 3));
        overlay.show(doc, block);
        assertEquals(block, overlay.ranges());
        overlay.clear();
        assertEquals(List.of(), overlay.ranges());
    }

    @Test
    void theBlockIsShadedAndTheRestIsNot() {
        overlay.show(doc, List.of(new LineRange(1, 1, 3))); // "et" of "beta"
        Point inside = centerOf(7);   // the "e"
        Point outside = centerOf(13); // the "m" of "gamma", below the block
        RGB white = new RGB(255, 255, 255);
        ImageData painted = paintOnWhite();
        assertNotEquals(white, rgbAt(painted, inside), "inside the block");
        assertEquals(white, rgbAt(painted, outside), "outside the block");
    }

    @Test
    void nothingIsPaintedWithoutABlock() {
        overlay.show(doc, List.of());
        ImageData painted = paintOnWhite();
        assertEquals(new RGB(255, 255, 255), rgbAt(painted, centerOf(7)));
    }

    @Test
    void afterDisposeNothingIsShown() {
        overlay.show(doc, List.of(new LineRange(0, 0, 2)));
        overlay.dispose();
        assertEquals(List.of(), overlay.ranges());
    }

    /** The middle of the character at {@code offset}, in widget pixels. */
    private Point centerOf(int offset) {
        Point from = widget.getLocationAtOffset(offset);
        Point to = widget.getLocationAtOffset(offset + 1);
        return new Point((from.x + to.x) / 2, from.y + widget.getLineHeight() / 2);
    }

    /** Paints only the overlay (no text) onto a white image the widget's size. */
    private ImageData paintOnWhite() {
        Image image = new Image(widget.getDisplay(), 400, 200);
        try {
            GC gc = new GC(image);
            try {
                gc.setBackground(widget.getDisplay().getSystemColor(SWT.COLOR_WHITE));
                gc.fillRectangle(0, 0, 400, 200);
                overlay.paint(gc);
            } finally {
                gc.dispose();
            }
            return image.getImageData();
        } finally {
            image.dispose();
        }
    }

    private static RGB rgbAt(ImageData data, Point p) {
        return data.palette.getRGB(data.getPixel(p.x, p.y));
    }
}
