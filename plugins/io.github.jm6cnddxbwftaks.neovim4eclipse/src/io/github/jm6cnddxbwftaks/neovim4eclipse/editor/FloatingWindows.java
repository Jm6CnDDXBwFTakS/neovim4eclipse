// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Control;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FloatPlacement;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FloatWindow;

/**
 * Draws Neovim's floating windows over the editor's text (docs/ARCHITECTURE.md, "Neovim's windows
 * drawn from their grids"): a plug-in's panel of key hints, hover text, a progress window opened in
 * the background, a picker's prompt with Neovim's cursor in it. Eclipse draws the editor, so
 * without this such a window exists in Neovim and nobody sees it.
 *
 * <h2>How</h2>
 * One {@link GridCanvas} per window, a child of the editor's {@code StyledText}, so the editor's
 * edges cut off what does not fit. Each is placed by
 * {@link FloatPlacement} from the row and column Neovim gave the window, and placed again when
 * the editor is resized or paints itself. Why at a paint: when the text scrolls, {@code StyledText}
 * moves its children along with it ({@code scroll(..., all = true)}), and a float that does not
 * hang on text (a panel at the bottom) must stay where it is; a scroll repaints, so the paint puts
 * it back. A change of the editor's font, which changes the cell,
 * repaints too. Example: {@code <Space>} with which-key installed gives two windows, the
 * panel across the editor's bottom and a one-row line above it; the next key closes both.
 *
 * <h2>They only show</h2>
 * A drawn window never takes the keyboard focus ({@code SWT.NO_FOCUS}): the keys keep going to the
 * editor, and from there to Neovim, also when Neovim's cursor is in the window (a picker's prompt):
 * Neovim then puts them there itself. Its cursor is drawn as Neovim draws it by default: a block,
 * a bar at the cell's left edge in Insert and Replace mode ({@link #setInsertMode}). Clicks and
 * the wheel on it do nothing. The windows lie under the plug-in's own overlays (the command-line
 * window's panel), among themselves in Neovim's order ({@code zindex}), and over the status line's
 * row ({@link #keepUnder}), as Neovim draws a float over a status line: ui2's prompt and pager
 * windows reach it, and with {@code 'cmdheight'} 0 ui2's {@code :} line lies on it. Neovim's own command
 * line, prompts, pager and messages (ui2) are floats too, drawn like the others; the one with
 * Neovim's cursor is drawn with it ({@link #cursorCell}). While a plug-in's window has the cursor
 * ({@link #hasFocusedFloat}), the editor hides its own caret, so that one cursor shows where the
 * keys go.
 *
 * <p>UI thread only.
 */
final class FloatingWindows {

    private final Canvas host;
    /** The canvas of each window drawn, by window ID. */
    private final Map<Integer, GridCanvas> canvases = new LinkedHashMap<>();
    /** The windows drawn, lowest in the stack first. */
    private List<FloatWindow> shown = List.of();
    private final ControlListener placeOnResize = ControlListener.controlResizedAdapter(e -> place());
    private final PaintListener placeOnPaint = e -> place();
    private boolean listening;
    /** A child of the host kept under every window drawn: the status line's row, or {@code null}. */
    private Control under;
    /** Whether Neovim is in Insert or Replace mode: a cursor in a drawn window is a bar then. */
    private boolean insert;

    FloatingWindows(Canvas host) {
        this.host = host;
    }

    /** Keeps {@code control}, a child of the host, under every window drawn (the status line's row). */
    void keepUnder(Control control) {
        under = control;
        stack();
    }

    /**
     * Draws {@code windows} (lowest in the stack first) in place of the ones drawn before: a new
     * window gets a canvas, one that is gone loses it, the others are redrawn and placed again. An
     * empty list removes them all.
     */
    void show(List<FloatWindow> windows) {
        if (host.isDisposed()) {
            return;
        }
        shown = List.copyOf(windows);
        List<Integer> gone = new ArrayList<>(canvases.keySet());
        for (FloatWindow window : shown) {
            int id = window.grid().window();
            gone.remove(Integer.valueOf(id));
            GridCanvas canvas = canvases.get(id);
            if (canvas == null || canvas.isDisposed()) {
                canvas = new GridCanvas(host, GridCanvas.lookOf(host), SWT.NO_FOCUS);
                canvas.setInsertCursor(insert);
                // The wheel over a float does nothing; without this, some platforms scroll the editor under it.
                canvas.addListener(SWT.MouseVerticalWheel, e -> e.doit = false);
                canvas.addListener(SWT.MouseHorizontalWheel, e -> e.doit = false);
                canvases.put(id, canvas);
            }
            canvas.show(window.grid());
        }
        for (int id : gone) {
            GridCanvas canvas = canvases.remove(id);
            if (canvas != null && !canvas.isDisposed()) {
                canvas.dispose();
            }
        }
        if (!shown.isEmpty() && !listening) {
            host.addControlListener(placeOnResize);
            host.addPaintListener(placeOnPaint);
            listening = true;
        } else if (shown.isEmpty() && listening) {
            host.removeControlListener(placeOnResize);
            host.removePaintListener(placeOnPaint);
            listening = false;
        }
        place();
        stack();
    }

    /** Removes every window (Neovim mode off, the editor detaches). */
    void clear() {
        show(List.of());
    }

    /**
     * Whether Neovim is in Insert or Replace mode ({@code VimModes.isTyping}): the cursor in a drawn
     * window is a bar then, a block otherwise, as in Neovim's default {@code 'guicursor'}. Example:
     * a prompt buffer's window entered in Insert mode shows a bar after {@code "> "}; Esc in it, a block on
     * the last letter. {@code 'guicursor'} itself is not read: the editor's own caret and the
     * command-line window's panel do the same.
     */
    void setInsertMode(boolean insertMode) {
        if (insertMode == insert) {
            return;
        }
        insert = insertMode;
        for (GridCanvas canvas : canvases.values()) {
            if (!canvas.isDisposed()) {
                canvas.setInsertCursor(insertMode);
            }
        }
    }

    /**
     * Whether Neovim's cursor is in a plug-in's drawn window ({@link FloatWindow#focused}): a
     * picker's prompt, not ui2's command line. The editor hides its caret meanwhile.
     */
    boolean hasFocusedFloat() {
        return shown.stream().anyMatch(FloatWindow::focused);
    }

    /** Whether any window is drawn. */
    boolean isShowing() {
        return !canvases.isEmpty();
    }

    /**
     * Where Neovim's cursor is, when it is in a drawn window (ui2's command line while a {@code :}
     * line is typed, an entered pager): its cell, in the host's coordinates; {@code null} when no
     * drawn window has it. An input method's composition is drawn there, and its candidates listed
     * under it ({@code LineInputMethod}).
     */
    Rectangle cursorCell() {
        for (FloatWindow window : shown) {
            GridCanvas canvas = canvases.get(window.grid().window());
            if (canvas == null || canvas.isDisposed() || window.grid().cursorRow() < 0) {
                continue;
            }
            Rectangle at = canvas.getBounds();
            return new Rectangle(at.x + window.grid().cursorCol() * canvas.cellWidth(),
                    at.y + window.grid().cursorRow() * canvas.cellHeight(), canvas.cellWidth(), canvas.cellHeight());
        }
        return null;
    }

    /** The canvas of window {@code win}, or {@code null}; for tests. */
    GridCanvas canvas(int win) {
        return canvases.get(win);
    }

    /** Removes the windows and the listener. */
    void dispose() {
        if (!host.isDisposed()) {
            clear();
        } else {
            canvases.clear(); // the canvases went with their parent
        }
    }

    /**
     * Puts each canvas where its window belongs. Only a canvas that is somewhere else is moved:
     * this runs at each paint of the editor, and moving a child repaints.
     */
    private void place() {
        if (host.isDisposed()) {
            return;
        }
        Rectangle area = host.getClientArea();
        for (FloatWindow window : shown) {
            GridCanvas canvas = canvases.get(window.grid().window());
            if (canvas == null || canvas.isDisposed()) {
                continue;
            }
            FloatPlacement.Box box = FloatPlacement.place(window, canvas.cellWidth(), canvas.cellHeight(), area.width,
                    area.height);
            Rectangle bounds = new Rectangle(area.x + box.x(), area.y + box.y(), box.width(), box.height());
            if (!bounds.equals(canvas.getBounds())) {
                canvas.setBounds(bounds);
            }
        }
    }

    /**
     * Puts the canvases in Neovim's order under the editor's other children: the highest is moved
     * to the bottom first, then each lower one below it. Only when the windows change, not at a
     * paint: a change of order repaints, and would paint again without end.
     */
    private void stack() {
        for (int i = shown.size() - 1; i >= 0; i--) {
            GridCanvas canvas = canvases.get(shown.get(i).grid().window());
            if (canvas != null && !canvas.isDisposed()) {
                canvas.moveBelow(null);
            }
        }
        if (under != null && !under.isDisposed()) {
            under.moveBelow(null);
        }
    }
}
