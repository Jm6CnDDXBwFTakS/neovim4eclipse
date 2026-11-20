// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.IntSupplier;
import java.util.function.ObjIntConsumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Listener;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;

/**
 * Neovim's status line of the editor's window, drawn in a row under the editor's text, as Neovim
 * lays out its screen: the window's text, then its status line. Example: {@code notes.txt [+]} on
 * the left and {@code 12,5  Top} on the right, in Neovim's {@code StatusLine} colors (lualine's
 * sections, if the user has it), and in {@code StatusLineNC} once another editor has the keyboard.
 *
 * <h2>Where: the text widget's bottom margin</h2>
 * The row is the {@code StyledText}'s bottom margin, made one line higher while a status line is
 * shown, with a {@link GridCanvas} over it as the widget's child. A margin is not text: Eclipse
 * never draws or scrolls text into it, so no line hides behind the row, and another editor's
 * layout (the rulers, the overview ruler) is left as it is. {@code ViewportFollower} counts the
 * editor's rows without the margin, so Neovim's window gets exactly the rows shown, and its status
 * line is the row below them, as here. The margin the widget had before is kept and restored.
 *
 * <h2>What</h2>
 * The lines come from Neovim, evaluated per window ({@code LuaScripts.STATUS_LINE},
 * {@code util/StatusLines}), for every window; this row shows its own window's ({@code window}).
 * A line of no rows ({@code 'laststatus'} 0 or 1) takes the row away, and the margin with it.
 *
 * <p>UI thread only. Tested in {@code tests/swt} ({@code StatusLineRowTest}).
 */
final class StatusLineRow {

    private final StyledText text;
    private final IntSupplier window;
    private final GridCanvas canvas;
    /** Hears this editor's width in columns for its window, so that Neovim fits the line to it. */
    private final ObjIntConsumer<Integer> width;
    /** The window and columns last passed to {@link #width}. */
    private int widthWindow = -1;
    private int widthColumns = -1;
    private final Listener onChange = e -> place();
    /** The margin this row added to the widget's bottom margin, in pixels; 0 while hidden. */
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
        canvas.setVisible(false);
        text.addListener(SWT.Resize, onChange);
        text.addListener(SWT.Paint, onChange); // a new font or zoom changes the line height
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
        int height = shown ? text.getLineHeight() : 0;
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
        Rectangle bounds = new Rectangle(0, Math.max(0, area.height - height), area.width, height);
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
