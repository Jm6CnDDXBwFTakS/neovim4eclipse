// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;

/**
 * The mouse on a grid Neovim draws, sent to Neovim as {@code nvim_input_mouse} takes it: the left
 * button (press, drag, release) and the vertical wheel. The right and middle buttons are left
 * alone: there is no Eclipse text under the grid for them. Used by the command-line window's panel
 * ({@link NeovimWindowPanel}) and the Neovim view ({@link NeovimView}). One per canvas, which
 * owns it: its state goes with the canvas.
 *
 * <p>Example: a click on row 2, column 5 sends {@code ("left", "press", "", 2, 5)} and
 * {@code ("left", "release", "", 2, 5)}; one notch of the wheel away from the user sends
 * {@code ("wheel", "up", "", row, col)}. UI thread only.
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
    /** The wheel's counts not yet sent: SWT counts 3 per notch, a touchpad less per event. */
    private int wheelCount;

    /** Listens to {@code canvas}'s mouse from now on, as long as it lives. */
    GridMouse(GridCanvas canvas, Target target) {
        this.canvas = canvas;
        this.target = target;
        canvas.addListener(SWT.MouseDown, this::onMouseDown);
        canvas.addListener(SWT.MouseMove, this::onMouseMove);
        canvas.addListener(SWT.MouseUp, this::onMouseUp);
        canvas.addListener(SWT.MouseVerticalWheel, this::onWheel);
    }

    private void onMouseDown(Event e) {
        int[] cell = canvas.cellAt(e.x, e.y);
        if (e.button != 1 || cell == null) {
            return; // the right and middle buttons do nothing here
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
        if (e.button != 1 || pressedAt == null || cell == null) {
            return;
        }
        pressedAt = null;
        target.mouse("left", "release", modifiers(e.stateMask), cell[0], cell[1]);
    }

    /**
     * One wheel notch (SWT's count of 3) is one {@code <ScrollWheelUp>} or {@code <ScrollWheelDown>}:
     * Neovim scrolls by its {@code 'mousescroll'}. A touchpad sends many smaller counts; they add
     * up, so a swipe scrolls as far as the same turn of a wheel would, not one scroll per event.
     */
    private void onWheel(Event e) {
        e.doit = false; // what lies under the grid does not scroll too
        int[] cell = canvas.cellAt(e.x, e.y);
        if (cell == null || e.count == 0) {
            return;
        }
        if (Integer.signum(e.count) != Integer.signum(wheelCount)) {
            wheelCount = 0; // turned the other way: what was left of the old direction goes
        }
        wheelCount += e.count;
        while (Math.abs(wheelCount) >= 3) {
            target.mouse("wheel", wheelCount > 0 ? "up" : "down", modifiers(e.stateMask), cell[0], cell[1]);
            wheelCount -= 3 * Integer.signum(wheelCount);
        }
    }

    /** Neovim's modifier letters for a mouse event's keys: {@code "S"} Shift, {@code "C"} Ctrl, {@code "A"} Alt. */
    static String modifiers(int stateMask) {
        return ((stateMask & SWT.SHIFT) != 0 ? "S" : "") + ((stateMask & SWT.CTRL) != 0 ? "C" : "")
                + ((stateMask & SWT.ALT) != 0 ? "A" : "");
    }
}
