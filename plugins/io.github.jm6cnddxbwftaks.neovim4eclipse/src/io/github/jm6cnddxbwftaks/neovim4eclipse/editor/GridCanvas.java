// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridEvent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;

/**
 * Paints one of Neovim's window grids ({@link GridSnapshot}) the way a terminal would: a cell per
 * character, in the editor's font, each cell as wide as the font's average character and as high as
 * the editor's line. Used for the command-line window ({@link NeovimWindowPanel}).
 *
 * <h2>Colors</h2>
 * A cell's colors come from its highlight ({@link GridSnapshot#colors}): Neovim's colors where its
 * highlight group sets them (syntax, titles, the visual selection), the editor's own text and
 * background colors where it leaves them to the default. Bold and italic use the editor's font in
 * that style; underline and strikethrough are drawn as lines.
 *
 * <h2>The cursor</h2>
 * Neovim's cursor, when it is on this grid, is a block (the cell in reversed colors), or a thin bar
 * at the cell's left edge in Insert mode ({@link #setInsertCursor}), as Neovim's own default
 * {@code 'guicursor'} draws it.
 *
 * <p>UI thread only. Paints all of itself ({@code SWT.NO_BACKGROUND}, double-buffered against
 * flicker).
 */
final class GridCanvas extends Canvas {

    /** Width of the Insert-mode cursor bar, in pixels. */
    static final int BAR_WIDTH = 2;

    private final StyledText host;
    private GridSnapshot grid;
    private boolean insertCursor;
    private Font bold;
    private Font italic;
    private Font boldItalic;
    private Font fontOfStyles; // the host font the styled ones were made from
    private Font fontOfWidth; // the host font cellWidth was measured for
    private int width;

    GridCanvas(Composite parent, StyledText host) {
        super(parent, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        this.host = host;
        addPaintListener(e -> paint(e.gc));
        addDisposeListener(e -> disposeFonts());
    }

    /** Shows {@code snapshot} (a new state of the grid), redrawn at once. */
    void show(GridSnapshot snapshot) {
        grid = snapshot;
        redraw();
    }

    /** Whether the cursor is drawn as Insert mode's bar instead of a block. */
    void setInsertCursor(boolean insert) {
        if (insert != insertCursor) {
            insertCursor = insert;
            redraw();
        }
    }

    GridSnapshot grid() {
        return grid;
    }

    /** One cell's width: the editor font's average character width, measured once per font. */
    int cellWidth() {
        Font font = host.getFont();
        if (font != fontOfWidth) {
            GC gc = new GC(this);
            try {
                gc.setFont(font);
                width = Math.max(1, (int) Math.round(gc.getFontMetrics().getAverageCharacterWidth()));
                fontOfWidth = font;
            } finally {
                gc.dispose();
            }
        }
        return width;
    }

    /**
     * The grid cell under point ({@code x}, {@code y}) of the canvas, as {row, column}, kept inside the
     * grid (a drag past its edge stays on the last row or column, as in a terminal), or {@code null}
     * before the grid is drawn.
     */
    int[] cellAt(int x, int y) {
        if (grid == null || grid.width() < 1 || grid.height() < 1) {
            return null;
        }
        int row = Math.clamp(Math.floorDiv(y, cellHeight()), 0, grid.height() - 1);
        int col = Math.clamp(Math.floorDiv(x, cellWidth()), 0, grid.width() - 1);
        return new int[] { row, col };
    }

    /** One cell's height: the editor's line height. */
    int cellHeight() {
        return Math.max(1, host.getLineHeight());
    }

    /** The size that shows every cell of the grid; one cell when there is none yet. */
    Point gridSize() {
        int columns = grid == null ? 1 : Math.max(1, grid.width());
        int rows = grid == null ? 1 : Math.max(1, grid.height());
        return new Point(columns * cellWidth(), rows * cellHeight());
    }

    /**
     * Paints the grid into {@code gc}: background first, then each cell's text, then the cursor.
     * Package-visible for the pixel tests, which paint into an image.
     */
    void paint(GC gc) {
        Point size = getSize();
        int editorFg = rgbOf(host.getForeground());
        int editorBg = rgbOf(OverlayFrame.intendedBackground(host));
        gc.setBackground(color(editorBg));
        gc.fillRectangle(0, 0, Math.max(size.x, 1), Math.max(size.y, 1));
        if (grid == null) {
            return;
        }
        int w = cellWidth();
        int h = cellHeight();
        gc.setFont(host.getFont());
        int ascentShift = Math.max(0, (h - gc.getFontMetrics().getHeight()) / 2); // text centered in the line
        for (int row = 0; row < grid.height(); row++) {
            for (int col = 0; col < grid.width(); col++) {
                int[] colors = grid.colors(grid.hlAt(row, col), editorFg, editorBg);
                boolean cursorHere = row == grid.cursorRow() && col == grid.cursorCol() && !insertCursor;
                int fg = cursorHere ? colors[1] : colors[0];
                int bg = cursorHere ? colors[0] : colors[1];
                int x = col * w;
                int y = row * h;
                if (bg != editorBg || cursorHere) {
                    gc.setBackground(color(bg));
                    gc.fillRectangle(x, y, w, h);
                }
                String text = grid.textAt(row, col);
                if (text.isEmpty() || text.isBlank()) {
                    continue; // blank, or the right half of a double-width character drawn before
                }
                GridEvent.HlAttr attr = grid.attr(grid.hlAt(row, col));
                gc.setFont(fontFor(attr.bold(), attr.italic()));
                gc.setForeground(color(fg));
                gc.drawString(text, x, y + ascentShift, true);
                if (attr.underline()) {
                    gc.drawLine(x, y + h - 1, x + w - 1, y + h - 1);
                }
                if (attr.strikethrough()) {
                    gc.drawLine(x, y + h / 2, x + w - 1, y + h / 2);
                }
            }
        }
        if (insertCursor && grid.cursorRow() >= 0) {
            int[] colors = grid.colors(grid.hlAt(grid.cursorRow(), grid.cursorCol()), editorFg, editorBg);
            gc.setBackground(color(colors[0]));
            gc.fillRectangle(grid.cursorCol() * w, grid.cursorRow() * h, BAR_WIDTH, h);
        }
    }

    private Font fontFor(boolean isBold, boolean isItalic) {
        Font base = host.getFont();
        if (!isBold && !isItalic) {
            return base;
        }
        if (fontOfStyles != base) {
            disposeFonts();
            fontOfStyles = base;
        }
        if (isBold && isItalic) {
            return boldItalic != null ? boldItalic : (boldItalic = styled(base, SWT.BOLD | SWT.ITALIC));
        }
        if (isBold) {
            return bold != null ? bold : (bold = styled(base, SWT.BOLD));
        }
        return italic != null ? italic : (italic = styled(base, SWT.ITALIC));
    }

    private Font styled(Font base, int style) {
        FontData[] data = base.getFontData();
        for (FontData d : data) {
            d.setStyle(d.getStyle() | style);
        }
        return new Font(getDisplay(), data);
    }

    private void disposeFonts() {
        for (Font f : new Font[] { bold, italic, boldItalic }) {
            if (f != null && !f.isDisposed()) {
                f.dispose();
            }
        }
        bold = null;
        italic = null;
        boldItalic = null;
    }

    /** SWT 4.17+: a {@link Color} from RGB values holds no system resource and needs no dispose. */
    private static Color color(int rgb) {
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    private static int rgbOf(Color color) {
        return (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
    }
}
