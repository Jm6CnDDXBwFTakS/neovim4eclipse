// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WheelScroll;

/**
 * The mouse on a grid Neovim draws, sent to Neovim as {@code nvim_input_mouse} takes it: the left
 * button (press, drag, release), the middle button (press, release: Neovim pastes register
 * {@code *}, or runs a mapping of {@code <MiddleMouse>}) and the wheel, both ways. The right button
 * is left alone: Neovim's answer to it is a popup menu that waits for a key. Used by the command-line window's panel
 * ({@link NeovimWindowPanel}), the page editors ({@link NeovimPageEditor}) and plug-ins' floating
 * windows ({@link FloatingWindows}). One per canvas, which
 * owns it: its state goes with the canvas.
 *
 * <p><b>Never a press or a wheel with Ctrl or Cmd held</b>, on any of them, as in the editor's text
 * ({@link NeovimMouse}): those are Eclipse's hyperlink keys. Sent, Ctrl+click would be Vim's raw tag
 * jump ({@code <C-LeftMouse>}), past the mapping that gives {@code Ctrl-]} to Open Declaration, on a
 * {@code tags} file a cloned repository may hold.
 *
 * <p>Example: a click on row 2, column 5 sends {@code ("left", "press", "", 2, 5)} and
 * {@code ("left", "release", "", 2, 5)}; one notch of the wheel away from the user sends
 * {@code ("wheel", "up", "", row, col)}, and one to the left {@code ("wheel", "left", "", row, col)}.
 * UI thread only.
 */
final class GridMouse {

    /** Where the events go; {@code nvim_input_mouse}'s arguments, the grid being the canvas's. */
    interface Target {
        void mouse(String button, String action, String modifier, int row, int col);
    }

    private final GridCanvas canvas;
    private final Target target;
    /** The cell the left button last pressed or dragged to, {row, col}; {@code null} while it is up. */
    private int[] pressedAt;
    /** Whether the middle button was pressed here and not yet released. */
    private boolean middlePressed;
    /** The wheel's counts not yet sent: a notch is {@link WheelScroll#COUNT_PER_NOTCH}, a touchpad less per event. */
    private int wheelCount;
    /** The same for the sideways wheel. */
    private int sidewaysCount;

    /** Listens to {@code canvas}'s mouse from now on, as long as it lives. */
    GridMouse(GridCanvas canvas, Target target) {
        this.canvas = canvas;
        this.target = target;
        canvas.addListener(SWT.MouseDown, this::onMouseDown);
        canvas.addListener(SWT.MouseMove, this::onMouseMove);
        canvas.addListener(SWT.MouseUp, this::onMouseUp);
        canvas.addListener(SWT.MouseVerticalWheel, this::onWheel);
        canvas.addListener(SWT.MouseHorizontalWheel, this::onSidewaysWheel);
    }

    private void onMouseDown(Event e) {
        int[] cell = canvas.cellAt(e.x, e.y);
        if (cell == null || leftOut(e)) {
            return;
        }
        if (e.button == 2) {
            middlePressed = true;
            target.mouse("middle", "press", modifiers(e.stateMask), cell[0], cell[1]);
            return;
        }
        if (e.button != 1) {
            return; // the right button does nothing here
        }
        pressedAt = cell;
        target.mouse("left", "press", modifiers(e.stateMask), cell[0], cell[1]);
    }

    /**
     * A drag sends only a change of cell: moving inside one cell would make Neovim redraw for
     * nothing. Only with the left button held: a press whose release was lost (the window closed
     * mid-drag) never turns a hover into a drag.
     */
    private void onMouseMove(Event e) {
        int[] cell = pressedAt == null || (e.stateMask & SWT.BUTTON1) == 0 ? null : canvas.cellAt(e.x, e.y);
        if (cell == null || (cell[0] == pressedAt[0] && cell[1] == pressedAt[1])) {
            return;
        }
        pressedAt = cell;
        target.mouse("left", "drag", modifiers(e.stateMask), cell[0], cell[1]);
    }

    private void onMouseUp(Event e) {
        int[] cell = canvas.cellAt(e.x, e.y);
        if (e.button == 2 && middlePressed) {
            middlePressed = false;
            if (cell != null) {
                target.mouse("middle", "release", modifiers(e.stateMask), cell[0], cell[1]);
            }
            return;
        }
        if (e.button != 1 || pressedAt == null || cell == null) {
            return;
        }
        pressedAt = null;
        target.mouse("left", "release", modifiers(e.stateMask), cell[0], cell[1]);
    }

    /**
     * One wheel notch ({@link WheelScroll#COUNT_PER_NOTCH}) is one {@code <ScrollWheelUp>} or {@code <ScrollWheelDown>}:
     * Neovim scrolls by its {@code 'mousescroll'}. A touchpad sends many smaller counts; they add
     * up, so a swipe scrolls as far as the same turn of a wheel would, not one scroll per event.
     */
    private void onWheel(Event e) {
        e.doit = false; // what lies under the grid does not scroll too
        int[] cell = canvas.cellAt(e.x, e.y);
        if (cell == null || e.count == 0 || leftOut(e)) {
            return;
        }
        if (Integer.signum(e.count) != Integer.signum(wheelCount)) {
            wheelCount = 0; // turned the other way: what was left of the old direction goes
        }
        wheelCount += e.count;
        while (Math.abs(wheelCount) >= WheelScroll.COUNT_PER_NOTCH) {
            target.mouse("wheel", wheelCount > 0 ? "up" : "down", modifiers(e.stateMask), cell[0], cell[1]);
            wheelCount -= WheelScroll.COUNT_PER_NOTCH * Integer.signum(wheelCount);
        }
    }

    /**
     * The sideways wheel, counted as {@link #onWheel} counts. A positive count is to the left on
     * every system (SWT's {@code MouseHorizontalWheel}: GTK's and Cocoa's sign, and the one it
     * flips Windows' to). Neovim scrolls a window of {@code 'nowrap'} text by {@code 'mousescroll'}.
     */
    private void onSidewaysWheel(Event e) {
        e.doit = false; // what lies under the grid does not scroll too
        int[] cell = canvas.cellAt(e.x, e.y);
        if (cell == null || e.count == 0 || leftOut(e)) {
            return;
        }
        if (Integer.signum(e.count) != Integer.signum(sidewaysCount)) {
            sidewaysCount = 0;
        }
        sidewaysCount += e.count;
        while (Math.abs(sidewaysCount) >= WheelScroll.COUNT_PER_NOTCH) {
            target.mouse("wheel", sidewaysCount > 0 ? "left" : "right", modifiers(e.stateMask), cell[0], cell[1]);
            sidewaysCount -= WheelScroll.COUNT_PER_NOTCH * Integer.signum(sidewaysCount);
        }
    }

    private static boolean leftOut(Event e) {
        return (e.stateMask & (SWT.MOD1 | SWT.CTRL)) != 0;
    }

    /** Neovim's modifier letters for a mouse event's keys: {@code "S"} Shift, {@code "C"} Ctrl, {@code "A"} Alt. */
    static String modifiers(int stateMask) {
        return ((stateMask & SWT.SHIFT) != 0 ? "S" : "") + ((stateMask & SWT.CTRL) != 0 ? "C" : "")
                + ((stateMask & SWT.ALT) != 0 ? "A" : "");
    }
}
