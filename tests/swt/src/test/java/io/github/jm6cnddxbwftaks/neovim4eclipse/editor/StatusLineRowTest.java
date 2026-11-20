// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusLines;

/**
 * {@link StatusLineRow}: Neovim's status line of the editor's window in a row under its text, the
 * {@code StyledText}'s bottom margin made one line higher while a line is shown, with the canvas
 * over it. The line itself is {@code StatusLinesTest}'s and {@code status_line_spec.lua}'s.
 */
class StatusLineRowTest {

    private static final int WINDOW = 1000;

    private Shell shell;
    private StyledText text;
    private StatusLineRow row;

    @BeforeEach
    void open() {
        shell = new Shell(Display.getDefault());
        shell.setLayout(new FillLayout());
        text = new StyledText(shell, SWT.MULTI | SWT.V_SCROLL);
        text.setText("one\ntwo\nthree");
        shell.setSize(400, 300);
        shell.open();
        shell.layout(true, true);
    }

    @AfterEach
    void close() {
        shell.dispose();
    }

    /** A line of {@code window} as {@code LuaScripts.STATUS_LINE} answers it, one highlight. */
    private static GridSnapshot line(int window, String content) {
        List<String> cells = content.codePoints().mapToObj(Character::toString).toList();
        List<Integer> hl = cells.stream().map(c -> 1).toList();
        return StatusLines.of(window, List.of(true, cells, hl, List.of(List.of(0xFFFFFF, 0x333333, false, true, false, false, false))));
    }

    /** Each {@code (window, columns)} the row reported. */
    private final List<int[]> widths = new java.util.ArrayList<>();

    private StatusLineRow row() {
        row = new StatusLineRow(text, () -> WINDOW, (win, columns) -> widths.add(new int[] { win, columns }));
        return row;
    }

    /**
     * The row tells Neovim the editor's width in columns (the client area over the cell width), once
     * per change: a background editor's window is as wide as the focused editor's, not its own.
     */
    @Test
    void theEditorsWidthInColumnsIsReportedOncePerChange() {
        row().show(line(WINDOW, "a.txt"));
        int columns = text.getClientArea().width / row.canvasForTests().cellWidth();
        assertEquals(1, widths.size(), "once");
        assertEquals(WINDOW, widths.get(0)[0]);
        assertEquals(columns, widths.get(0)[1]);
        row.show(line(WINDOW, "b.txt"));
        assertEquals(1, widths.size(), "the same width is not reported again");
        shell.setSize(300, 300);
        shell.layout(true, true);
        text.notifyListeners(SWT.Resize, new org.eclipse.swt.widgets.Event());
        assertEquals(2, widths.size(), "a narrower editor reports again");
        assertTrue(widths.get(1)[1] < columns);
    }

    @Test
    void aShownLineAddsOneLineOfMarginAndDisposeTakesItBack() {
        int before = text.getBottomMargin();
        row().show(line(WINDOW, "a.txt  1,1"));
        assertEquals(before + text.getLineHeight(), text.getBottomMargin());
        assertTrue(row.canvasForTests().isVisible());
        assertEquals("a.txt  1,1", row.canvasForTests().grid().row(0));

        row.dispose();
        assertEquals(before, text.getBottomMargin(), "the widget's own margin back");
        assertTrue(row.canvasForTests().isDisposed());
    }

    /** A margin the widget had before (another plug-in's, a theme's) is kept under the row's. */
    @Test
    void anExistingMarginIsKept() {
        text.setBottomMargin(7);
        row().show(line(WINDOW, "x"));
        assertEquals(7 + text.getLineHeight(), text.getBottomMargin());
        row.show(line(WINDOW, "y")); // a second line adds nothing more
        assertEquals(7 + text.getLineHeight(), text.getBottomMargin());
        row.dispose();
        assertEquals(7, text.getBottomMargin());
    }

    /** Every window's lines reach every editor; one shows its own window's only. */
    @Test
    void otherWindowsLinesAreIgnored() {
        int before = text.getBottomMargin();
        row().show(line(WINDOW + 1, "other.txt"));
        assertEquals(before, text.getBottomMargin());
        assertFalse(row.canvasForTests().isVisible());

        row.show(line(WINDOW, "mine.txt"));
        GridSnapshot mine = row.canvasForTests().grid();
        row.show(line(WINDOW + 1, "other.txt"));
        assertSame(mine, row.canvasForTests().grid(), "still its own window's line");
    }

    /** {@code 'laststatus'} 0, or 1 with one window: a line of no rows takes the row away. */
    @Test
    void aLineOfNoRowsHidesTheRow() {
        int before = text.getBottomMargin();
        row().show(line(WINDOW, "a.txt"));
        row.show(StatusLines.none(WINDOW));
        assertEquals(before, text.getBottomMargin());
        assertFalse(row.canvasForTests().isVisible());

        row.show(line(WINDOW, "a.txt"));
        assertEquals(before + text.getLineHeight(), text.getBottomMargin(), "and a line brings it back");
    }

    /** The canvas lies over the margin: the client area's last line height, its full width. */
    @Test
    void theCanvasIsAtTheBottomOfTheClientArea() {
        row().show(line(WINDOW, "a.txt"));
        Rectangle area = text.getClientArea();
        int height = text.getLineHeight();
        assertEquals(new Rectangle(0, area.height - height, area.width, height), row.canvasForTests().getBounds());

        shell.setSize(500, 200); // a resize moves it along
        shell.layout(true, true);
        area = text.getClientArea();
        assertEquals(new Rectangle(0, area.height - height, area.width, height), row.canvasForTests().getBounds());
    }
}
