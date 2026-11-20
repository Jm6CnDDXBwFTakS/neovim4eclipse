// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.IntSupplier;
import java.util.function.ObjIntConsumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Listener;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;

/**
 * Neovim's status line of the editor's window, drawn in a row under the editor's text, as Neovim
 * lays out its screen: the window's text, then its status line. Example: {@code notes.txt [+]} on
 * the left and {@code 12,5  Top} on the right, in Neovim's {@code StatusLine} colors (lualine's
 * sections, if the user has it), and in {@code StatusLineNC} once another editor has the keyboard.
 *
 * <h2>Where: the text widget's bottom margin</h2>
 * The rows are the {@code StyledText}'s bottom margin: the status line's, while one is shown, with a
 * {@link GridCanvas} over it as the widget's child, and under it the command line's
 * ({@link #commandLineRows}), where ui2 draws Neovim's {@code :} line and its messages. So the
 * editor is laid out as Neovim's screen: text, status line, command line. A margin is not text: Eclipse
 * never draws or scrolls text into it, so no line hides behind the row, and another editor's
 * layout (the rulers, the overview ruler) is left as it is. {@code ViewportFollower} counts the
 * editor's rows without the margin, so Neovim's window gets exactly the rows shown, and its status
 * line is the row below them, as here. The margin the widget had before is kept and restored.
 *
 * <h2>What</h2>
 * The lines come from Neovim, evaluated per window ({@code LuaScripts.STATUS_LINE},
 * {@code util/StatusLines}), for every window; this row shows its own window's ({@code window}).
 * A line of no rows ({@code 'laststatus'} 0 or 1) takes the status row away; the command line's
 * stays, unless the user's {@code 'cmdheight'} is 0 ({@link #commandLineRows}).
 *
 * <p>UI thread only. Tested in {@code tests/swt} ({@code StatusLineRowTest}).
 */
final class StatusLineRow {

    /** Marks the row's canvas among the widget's children, which Neovim's floats are too ({@link #isRow}). */
    private static final String DATA_KEY = StatusLineRow.class.getName();

    /**
     * Whether {@code control} is a status row's canvas, not a float's: both are {@link GridCanvas}
     * children of the editor's widget. Example: a test counting the floats drawn leaves it out.
     */
    static boolean isRow(org.eclipse.swt.widgets.Control control) {
        return Boolean.TRUE.equals(control.getData(DATA_KEY));
    }

    private final StyledText text;
    private final IntSupplier window;
    private final GridCanvas canvas;
    /** Hears this editor's width in columns for its window, so that Neovim fits the line to it. */
    private final ObjIntConsumer<Integer> width;
    /** The window and columns last passed to {@link #width}. */
    private int widthWindow = -1;
    private int widthColumns = -1;
    private final Listener onChange = e -> place();
    /**
     * The command line's rows under the status line: {@code 'cmdheight'} as the plug-in sets it
     * ({@code LuaScripts.UI2}), 1. ui2 draws the line there ({@code FloatingWindows}), as Neovim lays
     * out its screen. A user's larger {@code 'cmdheight'} still works: ui2's window then reaches up
     * over the status line, from the editor's bottom edge ({@code util/FloatPlacement}). A user's
     * {@code 'cmdheight'} 0 makes it 0 ({@code NeovimSession.commandLineRows}): no row is kept, and
     * ui2's line, drawn only while open, lies on the editor's bottom edge, over the status line, as
     * in Neovim with 0. Example: an editor of 30 rows shows 29 of text with 0, 28 with 1.
     */
    private int commandLineRows = COMMAND_LINE_ROWS;

    /** The command line's rows by default ({@code 'cmdheight'} 1, as the plug-in sets it). */
    static final int COMMAND_LINE_ROWS = 1;

    /** The margin this row added to the widget's bottom margin, in pixels. */
    private int added;
    private boolean shown;

    /**
     * @param text   the editor's text widget
     * @param window this editor's Neovim window id, asked at each line ({@code EditorWindow::winid})
     * @param width  hears {@code (window, columns)} whenever this editor's width in columns or its
     *               window changes: every tabpage shares Neovim's one screen, sized to the focused
     *               editor, so a background editor's window is not as wide as its editor, and Neovim
     *               is told how wide to fit its line ({@code LuaScripts.STATUS_LINE}'s {@code set_width})
     */
    StatusLineRow(StyledText text, IntSupplier window, ObjIntConsumer<Integer> width) {
        this.text = text;
        this.window = window;
        this.width = width;
        this.canvas = new GridCanvas(text, text, SWT.NO_FOCUS);
        canvas.setData(DATA_KEY, Boolean.TRUE);
        canvas.setVisible(false);
        text.addListener(SWT.Resize, onChange);
        text.addListener(SWT.Paint, onChange); // a new font or zoom changes the line height
        place(); // the command line's row is there from the start
    }

    /**
     * Shows {@code line} if it is this editor's window's: a line of no rows hides the row. Lines of
     * other windows are ignored.
     */
    void show(GridSnapshot line) {
        if (text.isDisposed() || line.window() != window.getAsInt()) {
            return;
        }
        shown = line.height() > 0;
        if (shown) {
            canvas.show(line);
        }
        place();
    }

    /** Keeps {@code rows} rows for the command line (0 or 1; see {@link #commandLineRows}). UI thread. */
    void commandLineRows(int rows) {
        if (rows != commandLineRows) {
            commandLineRows = rows;
            place();
        }
    }

    /** The row's canvas, which Neovim's floats lie over ({@code FloatingWindows.keepUnder}). */
    Control control() {
        return canvas;
    }

    /** The row's canvas, for tests. */
    GridCanvas canvasForTests() {
        return canvas;
    }

    /** Sets the margin and the canvas's bounds from the widget's size and line height. */
    private void place() {
        if (text.isDisposed()) {
            return;
        }
        reportWidth();
        int lineHeight = text.getLineHeight();
        int height = ((shown ? 1 : 0) + commandLineRows) * lineHeight;
        if (height != added) {
            int base = Math.max(0, text.getBottomMargin() - added);
            added = height;
            text.setBottomMargin(base + added); // redraws the widget; the next paint finds nothing to change
        }
        if (!shown) {
            canvas.setVisible(false);
            return;
        }
        Rectangle area = text.getClientArea();
        Rectangle bounds = new Rectangle(0, Math.max(0, area.height - height), area.width, lineHeight);
        if (!bounds.equals(canvas.getBounds())) {
            canvas.setBounds(bounds);
        }
        canvas.setVisible(true);
    }

    /**
     * Passes this editor's width in columns on when it or the window changed: the client area over
     * the cell width, as the row draws its cells. Example: 720 pixels of 9-pixel cells are 80.
     */
    private void reportWidth() {
        int win = window.getAsInt();
        int cell = canvas.cellWidth();
        if (cell <= 0) {
            return; // the font not measured yet: the next paint reports
        }
        int columns = Math.max(1, text.getClientArea().width / cell);
        if (win >= 0 && (win != widthWindow || columns != widthColumns)) {
            widthWindow = win;
            widthColumns = columns;
            width.accept(win, columns);
        }
    }

    /** Takes the row away and gives the widget its own margin back. */
    void dispose() {
        if (text.isDisposed()) {
            return;
        }
        text.removeListener(SWT.Resize, onChange);
        text.removeListener(SWT.Paint, onChange);
        if (added > 0) {
            text.setBottomMargin(Math.max(0, text.getBottomMargin() - added));
            added = 0;
        }
        canvas.dispose();
    }
}
