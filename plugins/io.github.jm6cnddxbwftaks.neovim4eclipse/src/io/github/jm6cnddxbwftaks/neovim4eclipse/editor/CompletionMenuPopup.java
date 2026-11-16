// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.ToIntFunction;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CompletionMenu;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;

/**
 * Draws Neovim's insert-mode completion list ({@code Ctrl-N}, {@code Ctrl-P}, the {@code Ctrl-X}
 * modes) at the word being completed (docs/ARCHITECTURE.md, "The completion list"). What the list
 * holds and how it is laid out is {@link CompletionMenu}'s, pure and unit-tested; this class asks
 * the editor where the word is and puts a {@link GridCanvas} there.
 *
 * <h2>The frame</h2>
 * The list sits in an {@link OverlayFrame}, the border the {@code :} box has, here
 * {@link #BORDER} pixels wide. Why: the
 * list's background is the colorscheme's {@code Pmenu}, and that can be nearly the editor's own
 * (Neovim's default colors over Eclipse's dark theme), so the unselected entries would run into
 * the code behind them. The border's color is made from the editor's colors, so it shows in any
 * colorscheme. Wider than the {@code :} box's single pixel: that box lies at the editor's edge,
 * the list in the middle of the code, where one pixel is easy to miss. The entries' text still
 * lines up with the word: the frame is placed one border further left.
 *
 * <h2>Where</h2>
 * Under the line of the word, its text lined up with the word; above the line when there is more
 * room there ({@link CompletionMenu#rows}). The word's place comes from the editor's own text
 * ({@link StyledText#getLocationAtOffset}), so folding, word wrap and the editor's font are all
 * accounted for. It is worked out again when the editor is resized or paints itself: a scroll
 * repaints, and {@code StyledText} moves its children along with the text when it scrolls, which
 * is right for a list hanging on a word, but the room above and below changes with it.
 *
 * <h2>It only shows</h2>
 * As Neovim's floating windows ({@link FloatingWindows}): never the keyboard focus
 * ({@code SWT.NO_FOCUS}), so the keys keep going to the editor and from there to Neovim, which
 * moves the selection; clicks and the wheel on it do nothing.
 *
 * <p>UI thread only.
 */
final class CompletionMenuPopup {

    /** The frame's width in pixels (class comment, "The frame"). */
    static final int BORDER = 3;

    private final StyledText host;
    /**
     * The widget offset where the word being completed starts, for the list's place; -1 when the
     * editor cannot tell (the line is folded away, the document changed under the list), and then
     * nothing is drawn until it can.
     */
    private final ToIntFunction<CompletionMenu.View> wordStart;
    /** The border around {@link #canvas}; this is what is placed, shown and disposed. */
    private OverlayFrame<GridCanvas> frame;
    /** The frame's content, the cells of the list; {@code null} when there is no frame. */
    private GridCanvas canvas;
    private CompletionMenu.View shown = CompletionMenu.View.HIDDEN;
    /** The first entry drawn, when the list is cut: kept between moves of the selection, as Vim scrolls. */
    private int first;
    /** The cells last handed to the canvas: a paint places the list again, and must not redraw the same. */
    private GridSnapshot drawn;
    /** What {@link #drawn} was laid out for; {@code null} when nothing is drawn. */
    private Layout drawnFor;

    /**
     * The inputs of a layout. The view is compared by identity: a new view from the session is a
     * new list or selection, and comparing the entries of two long lists at each paint would cost
     * what the comparison is there to save.
     */
    private record Layout(CompletionMenu.View view, int first, int rows, int columns) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Layout l && view == l.view && first == l.first && rows == l.rows && columns == l.columns;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(view) * 31 + first * 17 + rows * 7 + columns;
        }
    }
    private final ControlListener placeOnResize = ControlListener.controlResizedAdapter(e -> place());
    private final PaintListener placeOnPaint = e -> place();
    private boolean listening;

    CompletionMenuPopup(StyledText host, ToIntFunction<CompletionMenu.View> wordStart) {
        this.host = host;
        this.wordStart = wordStart;
    }

    /** Draws {@code view} in place of what was drawn; {@link CompletionMenu.View#HIDDEN} removes the list. */
    void show(CompletionMenu.View view) {
        if (host.isDisposed()) {
            return;
        }
        boolean wasShowing = shown.isShowing();
        shown = view;
        if (!view.isShowing()) {
            if (frame != null) {
                frame.dispose(); // takes the canvas with it
            }
            frame = null;
            canvas = null;
            drawn = null;
            drawnFor = null;
            first = 0;
            listen(false);
            return;
        }
        if (!wasShowing) {
            first = 0; // a new list starts at its top
        }
        if (canvas == null || canvas.isDisposed()) {
            frame = new OverlayFrame<>(host, parent -> new GridCanvas(parent, host, SWT.NO_FOCUS), BORDER);
            canvas = frame.content();
            frame.control().setVisible(false); // until it has a place: a new control is visible at 0,0
            // The wheel over the list does nothing; without this, some platforms scroll the editor under it.
            canvas.addListener(SWT.MouseVerticalWheel, e -> e.doit = false);
            canvas.addListener(SWT.MouseHorizontalWheel, e -> e.doit = false);
            frame.control().moveAbove(null); // over Neovim's floating windows: the list is what the user is working with
            drawn = null;
            drawnFor = null;
        }
        listen(true);
        place();
    }

    /** Removes the list (Neovim mode off, the editor detaches, the editor loses the focus). */
    void hide() {
        show(CompletionMenu.View.HIDDEN);
    }

    boolean isShowing() {
        return frame != null && !frame.isDisposed() && frame.control().getVisible();
    }

    /** The framed list, the control that has the list's place in the editor, or {@code null}; for tests. */
    Control control() {
        return frame == null ? null : frame.control();
    }

    /** The list's canvas, or {@code null}; for tests. */
    GridCanvas canvas() {
        return canvas;
    }

    void dispose() {
        if (!host.isDisposed()) {
            hide();
        } else {
            frame = null; // went with its parent
            canvas = null;
        }
    }

    private void listen(boolean on) {
        if (on && !listening) {
            host.addControlListener(placeOnResize);
            host.addPaintListener(placeOnPaint);
        } else if (!on && listening) {
            host.removeControlListener(placeOnResize);
            host.removePaintListener(placeOnPaint);
        }
        listening = on;
    }

    /**
     * Lays the list out for the room there is and puts it at the word. Only what changed is
     * touched: this runs at each paint of the editor, and new cells or new bounds repaint.
     */
    private void place() {
        if (host.isDisposed() || canvas == null || canvas.isDisposed() || !shown.isShowing()) {
            return;
        }
        int offset = wordStart.applyAsInt(shown);
        if (offset < 0 || offset > host.getCharCount()) {
            if (frame.control().getVisible()) {
                frame.control().setVisible(false);
            }
            return;
        }
        Rectangle area = host.getClientArea();
        Point word = host.getLocationAtOffset(offset);
        int lineHeight = host.getLineHeight(offset);
        int cellWidth = Math.max(1, canvas.cellWidth());
        int cellHeight = Math.max(1, canvas.cellHeight());
        int lineBottom = word.y + lineHeight;
        int borders = 2 * BORDER; // the frame's top and bottom (left and right) lines
        CompletionMenu.Rows rows = CompletionMenu.rows(shown.items().size(), shown.maxRows(),
                Math.max(0, (area.y + area.height - lineBottom - borders) / cellHeight),
                Math.max(0, (word.y - area.y - borders) / cellHeight));
        first = CompletionMenu.firstRow(first, shown.selected(), shown.items().size(), rows.rows());
        Layout layout = new Layout(shown, first, rows.rows(), Math.max(0, area.width - borders) / cellWidth);
        if (!layout.equals(drawnFor)) { // laid out anew only when something changed: a long list costs
            drawn = CompletionMenu.grid(shown, first, rows.rows(), layout.columns());
            drawnFor = layout; // after the cells are there: a failed layout is tried again
            canvas.show(drawn);
        }
        GridSnapshot grid = drawn;
        // The frame's size: the cells and a border on each side. Its place: where the cells go, one
        // border further left, so that the entries' text stays under the word's.
        int width = grid.width() * cellWidth + borders;
        int height = grid.height() * cellHeight + borders;
        int left = CompletionMenu.left(word.x - area.x - BORDER, cellWidth, width, area.width);
        Rectangle bounds = new Rectangle(area.x + left, rows.below() ? lineBottom : word.y - height, width, height);
        Control control = frame.control();
        if (!bounds.equals(control.getBounds())) {
            control.setBounds(bounds);
        }
        if (!control.getVisible()) {
            control.setVisible(true);
        }
    }
}
