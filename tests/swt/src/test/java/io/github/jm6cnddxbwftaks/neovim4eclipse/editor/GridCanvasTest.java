// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridEvent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;

/**
 * {@link GridCanvas} painting into an image, so the test does not depend on the window being
 * visible: a cell's background is its highlight's, or the editor's where the highlight leaves it to
 * the default; the cursor is a block, or a bar in Insert mode.
 */
class GridCanvasTest {

    private static final RGB WHITE = new RGB(255, 255, 255);
    private static final RGB BLACK = new RGB(0, 0, 0);
    private static final RGB RED = new RGB(0xCC, 0, 0);

    private Shell shell;
    private StyledText host;
    private GridCanvas canvas;

    @BeforeEach
    void create() {
        shell = new Shell(Display.getDefault());
        host = new StyledText(shell, SWT.MULTI);
        host.setBackground(new Color(255, 255, 255));
        host.setForeground(new Color(0, 0, 0));
        host.setSize(400, 200);
        canvas = new GridCanvas(shell, host);
        canvas.setSize(400, 200);
    }

    @AfterEach
    void dispose() {
        shell.dispose();
    }

    /** A 4x2 grid: row 0 "ab" in highlight 1 (red background), then blanks; the cursor at (1, 0). */
    private static GridSnapshot grid() {
        List<String> text = new ArrayList<>(List.of("a", "b", " ", " ", " ", " ", " ", " "));
        int[] hl = { 1, 1, 0, 0, 0, 0, 0, 0 };
        GridEvent.HlAttr red = new GridEvent.HlAttr(1, 0xFFFFFF, 0xCC0000, false, false, false, false, false);
        return new GridSnapshot(1004, 4, 2, text, hl, Map.of(1, red),
                new GridEvent.DefaultColors(0xE0E2EA, 0x14161B), 1, 0);
    }

    private ImageData paint() {
        Image image = new Image(Display.getDefault(), 400, 200);
        GC gc = new GC(image);
        try {
            canvas.paint(gc);
        } finally {
            gc.dispose();
        }
        ImageData data = image.getImageData();
        image.dispose();
        return data;
    }

    private static RGB rgbAt(ImageData data, int x, int y) {
        return data.palette.getRGB(data.getPixel(x, y));
    }

    @Test
    void aHighlightsBackgroundAndTheEditorsWhereItHasNone() {
        canvas.show(grid());
        ImageData image = paint();
        int w = canvas.cellWidth();
        int h = canvas.cellHeight();
        assertEquals(RED, rgbAt(image, w + w / 2, 1), "cell (0, 1): its highlight's red, the letter's edge aside");
        assertEquals(WHITE, rgbAt(image, 3 * w + w / 2, h / 2), "cell (0, 3): the editor's white, not Neovim's dark default");
    }

    @Test
    void theCursorIsABlockAndABarInInsertMode() {
        canvas.show(grid());
        int w = canvas.cellWidth();
        int h = canvas.cellHeight();
        assertEquals(BLACK, rgbAt(paint(), w / 2, h + h / 2), "the blank cell under the cursor in reversed colors");
        canvas.setInsertCursor(true);
        ImageData insert = paint();
        assertEquals(BLACK, rgbAt(insert, 0, h + h / 2), "the bar at the cell's left edge");
        assertNotEquals(BLACK, rgbAt(insert, GridCanvas.BAR_WIDTH + 1, h + h / 2), "the rest of the cell is not covered");
    }

    /**
     * A character wider than its cell reaches into the next one, and that cell's background must not
     * cover it: an icon from a fallback font is one cell to Neovim and more than one on screen. Here
     * a cell holds two letters, which surely do not fit, and the cell after it, itself blank, has a
     * red background: some of it is no longer red, where the second letter lies. Any pixel that is
     * not the red will do, so the check does not depend on how a platform smooths the letter's edge.
     */
    @Test
    void textWiderThanItsCellIsNotCoveredByTheNextCellsBackground() {
        List<String> text = new ArrayList<>(List.of("MM", " ", " ", " "));
        int[] hl = { 0, 1, 1, 0 };
        GridEvent.HlAttr red = new GridEvent.HlAttr(1, 0xFFFFFF, 0xCC0000, false, false, false, false, false);
        canvas.show(new GridSnapshot(1004, 4, 1, text, hl, Map.of(1, red),
                new GridEvent.DefaultColors(GridEvent.NO_COLOR, GridEvent.NO_COLOR), -1, -1));
        ImageData image = paint();
        int w = canvas.cellWidth();
        int h = canvas.cellHeight();
        boolean inkOnRed = false;
        for (int x = w; x < 2 * w && !inkOnRed; x++) {
            for (int y = 0; y < h && !inkOnRed; y++) {
                inkOnRed = !RED.equals(rgbAt(image, x, y));
            }
        }
        assertTrue(inkOnRed, "the second M shows in the next cell, over its background");
        assertEquals(RED, rgbAt(image, 2 * w + w / 2, 1), "the cell after it is still red");
    }

    @Test
    void theSizeShowsEveryCell() {
        canvas.show(grid());
        assertEquals(4 * canvas.cellWidth(), canvas.gridSize().x);
        assertEquals(2 * host.getLineHeight(), canvas.gridSize().y);
    }
}
