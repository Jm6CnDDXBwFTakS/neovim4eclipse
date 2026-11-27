// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;

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
 * <h2>The keyboard</h2>
 * A drawn window never takes the keyboard focus ({@code SWT.NO_FOCUS}): the keys keep going to the
 * editor, and from there to Neovim, also when Neovim's cursor is in the window (a picker's prompt):
 * Neovim then puts them there itself. Its cursor is drawn as Neovim draws it by default: a block,
 * a bar at the cell's left edge in Insert and Replace mode ({@link #setInsertMode}).
 *
 * <h2>The mouse</h2>
 * The left button and the wheel on a plug-in's window go to Neovim at that window's own cell
 * ({@link GridMouse}, {@link Mouse}), and Neovim and the plug-in decide what they do, as in a
 * terminal: a click on Telescope's results selects the row and the prompt keeps the keyboard, a
 * double click opens it, the wheel scrolls the window under the pointer. A window that takes no
 * clicks is not there for the mouse, as in Neovim ({@link FloatWindow#mouseFallsThrough}: a
 * plug-in's {@code mouse = false}): the click goes to the highest window under it that takes one,
 * else to the text ({@link Below}), and the drag and release of that press follow it there. A
 * notification over the code does not stop a click on the code. Neovim's own windows that take
 * none (ui2's, unless the cursor is in one; the popup menu) keep it. Ctrl+click and Cmd+click are left out, as in the
 * editor's text.
 *
 * <h2>Stacking</h2>
 * The windows lie under the plug-in's own overlays (the command-line
 * window's panel), among themselves in Neovim's drawing order ({@code zindex}, then
 * {@code compindex}: {@code util/GridEvent.WinPos}), and over the status line's
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

    /** Where a mouse event on a window goes; {@code nvim_input_mouse}'s arguments. */
    @FunctionalInterface
    interface Mouse {
        void mouse(int grid, String button, String action, String modifier, int row, int col);
    }

    /**
     * Where a mouse event goes that fell through every window: what the windows lie over.
     * {@code x} and {@code y} are in the host's coordinates.
     */
    @FunctionalInterface
    interface Below {
        void mouse(String button, String action, String modifier, int x, int y);
    }

    /** {@link #pressWentTo}: the press went to what lies below every window. */
    private static final int TO_BELOW = -2;
    /** {@link #pressWentTo}: the press went nowhere (Neovim's own window kept it). */
    private static final int NOWHERE = -3;

    private final Canvas host;
    private final Mouse mouse;
    private final Below below;
    /**
     * Where each button's press that fell through went, until its release: a window, or
     * {@link #TO_BELOW} or {@link #NOWHERE}. By button, as one can be pressed while another is held.
     */
    private final Map<String, Integer> pressWentTo = new HashMap<>();
    /** The pointer in the event being handled, on its canvas: a cell would stop at the window's edge. */
    private int pointerX;
    private int pointerY;
    /** The last cell a drag that fell through was sent to, {window, row, col}: a new cell sends the next. */
    private int[] lastDrag;
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

    /**
     * @param host  the control the windows are drawn over
     * @param mouse gets the mouse on a window that takes it; whether it is sent on is the owner's
     *              decision (an insert Eclipse types gets no clicks)
     */
    FloatingWindows(Canvas host, Mouse mouse) {
        this(host, mouse, (button, action, modifier, x, y) -> { });
    }

    /**
     * @param host  the control the windows are drawn over
     * @param mouse gets the mouse on a window that takes it, as above
     * @param below gets the mouse that fell through every window
     */
    FloatingWindows(Canvas host, Mouse mouse, Below below) {
        this.host = host;
        this.mouse = mouse;
        this.below = below;
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
                GridCanvas made = canvas;
                // Before GridMouse's listeners, so that mouseOn has the pointer of the same event.
                for (int type : new int[] { SWT.MouseDown, SWT.MouseUp, SWT.MouseVerticalWheel,
                        SWT.MouseHorizontalWheel }) {
                    canvas.addListener(type, e -> {
                        pointerX = e.x;
                        pointerY = e.y;
                    });
                }
                canvas.addListener(SWT.MouseMove, e -> dragThrough(made, e));
                new GridMouse(canvas, (button, action, modifier, row, col) -> mouseOn(id, button, action, modifier, row, col));
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

    /**
     * A mouse event on window {@code win}: sent to the grid it has now if it takes the mouse, else
     * on to what lies below it if it lets the mouse through ({@link FloatWindow#mouseFallsThrough}).
     */
    private void mouseOn(int win, String button, String action, String modifier, int row, int col) {
        boolean press = "press".equals(action);
        if (press) {
            pressWentTo.remove(button); // a press whose release was lost ends here
            lastDrag = null;
        } else if (pressWentTo.containsKey(button)) { // this press fell through: its drag and release follow
            if ("release".equals(action)) {
                Rectangle at = canvases.get(win).getBounds();
                sendTo(pressWentTo.remove(button), button, action, modifier, at.x + pointerX, at.y + pointerY);
            }
            return; // a drag: dragThrough, with the pointer past this window's edge too
        }
        int index = indexOf(win);
        if (index < 0) {
            return;
        }
        FloatWindow window = shown.get(index);
        if (window.mouseGrid() != -1) {
            mouse.mouse(window.mouseGrid(), button, action, modifier, row, col);
        } else if (window.mouseFallsThrough()) {
            Rectangle at = canvases.get(win).getBounds();
            int x = at.x + pointerX;
            int y = at.y + pointerY;
            int to = targetUnder(index, x, y);
            if (press) {
                pressWentTo.put(button, to);
            }
            sendTo(to, button, action, modifier, x, y);
        }
    }

    /**
     * The left button dragged after a press that fell through: sent on at the real pointer, so that
     * a selection goes on past the edge of the window it began under. Only with the button held.
     */
    private void dragThrough(GridCanvas canvas, Event e) {
        Integer to = pressWentTo.get("left");
        if (to != null && (e.stateMask & SWT.BUTTON1) != 0) {
            Rectangle at = canvas.getBounds();
            sendTo(to, "left", "drag", GridMouse.modifiers(e.stateMask), at.x + e.x, at.y + e.y);
        }
    }

    /** Sends a mouse event at ({@code x}, {@code y}) of the host to {@code to}, a window or a target. */
    private void sendTo(int to, String button, String action, String modifier, int x, int y) {
        if (to == TO_BELOW) {
            below.mouse(button, action, modifier, x, y);
        } else if (to != NOWHERE) {
            sendToWindow(to, button, action, modifier, x, y);
        }
    }

    /** The highest window under {@code index} at that point that takes the mouse, or what says where it goes. */
    private int targetUnder(int index, int x, int y) {
        for (int i = index - 1; i >= 0; i--) {
            FloatWindow window = shown.get(i);
            GridCanvas canvas = canvases.get(window.grid().window());
            if (canvas == null || canvas.isDisposed() || !canvas.getBounds().contains(x, y)) {
                continue;
            }
            if (window.mouseGrid() != -1) {
                return window.grid().window();
            }
            if (!window.mouseFallsThrough()) {
                return NOWHERE; // Neovim's own window: it keeps the click
            }
        }
        return TO_BELOW;
    }

    /** Sends the event to window {@code win}'s cell at that point, the nearest one when outside it (a drag). */
    private void sendToWindow(int win, String button, String action, String modifier, int x, int y) {
        int index = indexOf(win);
        GridCanvas canvas = canvases.get(win);
        if (index < 0 || canvas == null || canvas.isDisposed() || shown.get(index).mouseGrid() == -1) {
            return; // it closed since the press
        }
        FloatWindow window = shown.get(index);
        Rectangle at = canvas.getBounds();
        int row = Math.max(0, Math.min((y - at.y) / Math.max(1, canvas.cellHeight()), window.grid().height() - 1));
        int col = Math.max(0, Math.min((x - at.x) / Math.max(1, canvas.cellWidth()), window.grid().width() - 1));
        if ("drag".equals(action)) {
            int[] cell = { win, row, col };
            if (Arrays.equals(cell, lastDrag)) {
                return; // moving inside one cell would make Neovim redraw for nothing
            }
            lastDrag = cell;
        }
        mouse.mouse(window.mouseGrid(), button, action, modifier, row, col);
    }

    /** Where window {@code win} is in {@link #shown}, or -1. */
    private int indexOf(int win) {
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).grid().window() == win) {
                return i;
            }
        }
        return -1;
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
