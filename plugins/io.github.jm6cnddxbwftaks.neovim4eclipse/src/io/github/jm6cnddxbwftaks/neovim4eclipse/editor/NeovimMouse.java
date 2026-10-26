// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.MouseInput;

/**
 * Hands the left mouse button in the editor to Neovim, so that clicking and dragging behave as in
 * Neovim with {@code 'mouse'} on: a click moves the cursor, a double click selects a word (from a
 * bracket, up to its match), a triple click the line ({@code V}), a quadruple click a block, a drag
 * selects, Shift+click extends the selection, and mappings of {@code <2-LeftMouse>} apply.
 *
 * <h2>How</h2>
 * A display <em>filter</em> sees the editor widget's mouse events before the widget does, and
 * swallows the left button's press, moves while it is held, release and double-click event
 * ({@code event.type = SWT.None}: SWT then delivers the event to no listener). Each becomes a
 * {@link MouseInput} with the document position of the character under the pointer, which the
 * session hands to Neovim ({@code NeovimSession.mouseAsync}). Neovim counts the clicks itself (its
 * {@code 'mousetime'}), and its cursor and visual selection come back as for a key.
 *
 * <p>Swallowing is needed because the editor would otherwise act too: its own drag selection and
 * caret moves would fight Neovim's selection. Because the widget no longer sees the press, this
 * class gives it the focus.
 *
 * <h2>What stays Eclipse's</h2>
 * <ul>
 *   <li>Insert mode, Eclipse mode, and whenever {@code forward} says no: everything.</li>
 *   <li>Ctrl+click (Cmd+click on macOS): hyperlinks such as Open Declaration. On macOS Ctrl+click
 *       is also the context menu.</li>
 *   <li>The right button (context menu) and the middle button (paste of the primary selection on
 *       Linux).</li>
 *   <li>Moves without a button: hovers, hyperlink underlines.</li>
 * </ul>
 *
 * <p>Example: three quick clicks on line 7 send press and release three times at line 7; Neovim
 * enters {@code V}, and the editor shows line 7 selected. UI thread only.
 */
final class NeovimMouse {

    private final StyledText widget;
    /** Widget offset to document offset (folded code; see {@link WidgetOffsets}). */
    private final IntUnaryOperator toDocument;
    private final Supplier<IDocument> document;
    /** Whether Neovim handles the mouse now (Neovim mode, not insert mode). */
    private final BooleanSupplier forward;
    private final Consumer<MouseInput> neovim;
    /** Runs on every forwarded press (the message line hides, as on a click it sees itself). */
    private final Runnable onPress;
    private final Display display;
    private final Listener filter = this::onMouse;

    /** True from a forwarded press until its release: moves are drags, and the release is ours. */
    private boolean pressed;
    /** The last position sent, so that moves within one character send nothing. */
    private int[] lastPosition;

    /**
     * @param widget     the editor's text widget
     * @param toDocument widget offset to document offset
     * @param document   the editor's document
     * @param forward    asked at each press whether Neovim handles the mouse now
     * @param neovim     receives the events, in order
     * @param onPress    runs on each forwarded press
     */
    NeovimMouse(StyledText widget, IntUnaryOperator toDocument, Supplier<IDocument> document, BooleanSupplier forward,
            Consumer<MouseInput> neovim, Runnable onPress) {
        this.widget = widget;
        this.toDocument = toDocument;
        this.document = document;
        this.forward = forward;
        this.neovim = neovim;
        this.onPress = onPress;
        this.display = widget.getDisplay();
        for (int type : new int[] { SWT.MouseDown, SWT.MouseMove, SWT.MouseUp, SWT.MouseDoubleClick }) {
            display.addFilter(type, filter);
        }
    }

    /** Stops forwarding. UI thread. */
    void dispose() {
        for (int type : new int[] { SWT.MouseDown, SWT.MouseMove, SWT.MouseUp, SWT.MouseDoubleClick }) {
            display.removeFilter(type, filter);
        }
    }

    private void onMouse(Event event) {
        if (event.widget != widget || widget.isDisposed()) {
            return;
        }
        switch (event.type) {
            case SWT.MouseDown -> {
                if (!isNeovims(event)) {
                    return;
                }
                swallow(event);
                if (!widget.isFocusControl()) {
                    widget.setFocus(); // the widget did not see the press, so it would not take the focus
                }
                pressed = true;
                onPress.run();
                send(MouseInput.Action.PRESS, event, true);
            }
            case SWT.MouseDoubleClick -> {
                if (isNeovims(event)) {
                    swallow(event); // its presses went to Neovim, which counts the clicks itself
                }
            }
            case SWT.MouseMove -> {
                if (pressed) {
                    swallow(event);
                    send(MouseInput.Action.DRAG, event, false);
                }
            }
            case SWT.MouseUp -> {
                if (pressed && event.button == 1) {
                    swallow(event);
                    pressed = false;
                    send(MouseInput.Action.RELEASE, event, true);
                }
            }
            default -> { }
        }
    }

    /** A left-button event that Neovim handles now (see "What stays Eclipse's"). */
    private boolean isNeovims(Event event) {
        boolean hyperlinkOrMenu = (event.stateMask & (SWT.MOD1 | SWT.CTRL)) != 0;
        return event.button == 1 && !hyperlinkOrMenu && forward.getAsBoolean();
    }

    private static void swallow(Event event) {
        event.type = SWT.None;
        event.doit = false;
    }

    /**
     * Sends the event at the character under the pointer. A drag to the character it was already
     * on sends nothing; presses and releases are always sent (Neovim counts the presses).
     */
    private void send(MouseInput.Action action, Event event, boolean always) {
        int[] position = DocumentPositions.offsetToLineCol(document.get(),
                toDocument.applyAsInt(widgetOffsetAt(widget, event.x, event.y)));
        if (!always && lastPosition != null && lastPosition[0] == position[0] && lastPosition[1] == position[1]) {
            return;
        }
        lastPosition = position;
        String modifiers = MouseInput.modifiers((event.stateMask & SWT.SHIFT) != 0, (event.stateMask & SWT.ALT) != 0);
        neovim.accept(new MouseInput(action, modifiers, position[0], position[1]));
    }

    /**
     * The widget offset of the character under the point, not the nearest caret position: Neovim
     * works on characters (a click on the right half of {@code a} is still on {@code a}). Right of a
     * line's text it is the line's end (Neovim's past-the-end cell); above or below the text the
     * first or last line; a drag outside the widget keeps going in the line under the pointer.
     */
    static int widgetOffsetAt(StyledText widget, int x, int y) {
        int lineCount = widget.getLineCount();
        int line = Math.max(0, Math.min(widget.getLineIndex(y), lineCount - 1));
        int start = widget.getOffsetAtLine(line);
        int length = widget.getLine(line).length();
        if (length == 0) {
            return start;
        }
        int top = widget.getLinePixel(line);
        int bottom = line + 1 < lineCount ? widget.getLinePixel(line + 1) : top + widget.getLineHeight(start);
        int probeY = y >= top && y < bottom ? y : top + Math.max(0, (bottom - top) / 2);
        int offset = widget.getOffsetAtPoint(new Point(Math.max(0, x), probeY));
        if (offset >= 0) {
            // getOffsetAtPoint gives the nearest caret position: on the right half of "a" that is the
            // offset after it. The character is the one whose left edge is at or left of x.
            boolean leftOfIt = offset > start && widget.getLocationAtOffset(offset).x > x;
            return leftOfIt ? offset - 1 : offset;
        }
        Point lineStart = widget.getLocationAtOffset(start);
        return x < lineStart.x ? start : start + length;
    }
}
