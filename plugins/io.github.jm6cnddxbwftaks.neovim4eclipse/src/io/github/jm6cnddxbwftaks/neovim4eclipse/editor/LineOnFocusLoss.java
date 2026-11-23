// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;

/**
 * Abandons Neovim's open {@code :}, {@code /} or {@code ?} line when the keyboard leaves the
 * widget that typed it for another part of Eclipse, as a click in the text does
 * ({@code NeovimMouse}) and as leaving a field does; and moves Neovim out of a plug-in's float its
 * cursor is in (a picker's prompt), drawn in the widget, for the same reasons: it would be
 * invisible while another part has the keyboard, and take Neovim's keys when the user comes back.
 * The float stays open unless its plug-in closes it on leaving, as Telescope does.
 *
 * <h2>Why</h2>
 * Only the part with the keyboard draws Neovim's windows (ui2's line among them), so a line left
 * open is invisible while another part has the keyboard, and Neovim keeps it: coming back, it is
 * drawn again and the next {@code :} goes on its end. Example: {@code :messages} typed in a page,
 * a click in the Problems view, a click back and {@code :}: the line read {@code :messages:}.
 * Abandoned, the line is in Neovim's history ({@code Up} brings it back), and the next {@code :}
 * starts clean.
 *
 * <h2>Not when another application takes the keyboard</h2>
 * Switching to another application keeps the line, as in a terminal: one may come back with
 * something to paste into it. One check, after the focus has moved ({@code asyncExec}): Eclipse
 * then has no active shell. Any of Eclipse's own windows taking the keyboard (a detached view, a
 * dialog) counts as another part, so its line is abandoned too: it would be as invisible. The
 * keyboard going to a control inside the widget (the command-line window's panel, a float) does
 * not: that is still the editor.
 *
 * <p>UI thread only. Tested in {@code tests/swt} ({@code LineOnFocusLossTest}).
 */
final class LineOnFocusLoss {

    private final Control widget;
    private final BooleanSupplier lineOpen;
    private final Runnable abandon;
    private final IntConsumer leaveFloat;
    // The float is read as the keyboard leaves (floatNow, in the listener), not later: by then the
    // part that took the keyboard may have opened one of its own (a page and the code share one
    // Neovim), which must stay.
    private final Listener onFocusOut;

    /**
     * @param widget   the widget keys are typed into (the editor's text, a page's canvas)
     * @param lineOpen whether a line is open now, typed from this widget ({@code NeovimSession.commandLineOpen})
     * @param abandon  abandons the line ({@code <C-c>} to Neovim)
     * @param floatNow the plug-in's float Neovim's cursor is in, -1 for none
     *                 ({@code NeovimSession.pluginFloat}): read as the keyboard leaves
     * @param leaveFloat moves Neovim out of that float, its insert ended
     *                 ({@code NeovimSession.moveOutOfFloatAsync})
     */
    LineOnFocusLoss(Control widget, BooleanSupplier lineOpen, Runnable abandon, IntSupplier floatNow,
            IntConsumer leaveFloat) {
        this.widget = widget;
        this.lineOpen = lineOpen;
        this.abandon = abandon;
        this.leaveFloat = leaveFloat;
        this.onFocusOut = e -> later(floatNow.getAsInt());
        widget.addListener(SWT.FocusOut, onFocusOut);
    }

    /** @param floatAtLeave the float the cursor was in as the keyboard left, -1 for none */
    private void later(int floatAtLeave) {
        Display display = widget.getDisplay();
        display.asyncExec(() -> {
            if (widget.isDisposed() || insideWidget(display.getFocusControl())) {
                return; // back already, never really left (a transient focus change), or inside it
            }
            if (display.getActiveShell() == null) {
                return; // another application: the line and the float stay
            }
            if (lineOpen.getAsBoolean()) {
                abandon.run();
            }
            if (floatAtLeave != -1) {
                leaveFloat.accept(floatAtLeave);
            }
        });
    }

    /**
     * Whether {@code focus} is the widget or one of its children: the command-line window's panel
     * and Neovim's floats are drawn inside the editor's widget, so the keyboard going there has not
     * left the editor. Example: {@code q:} opens the panel, which takes the keyboard; abandoning
     * the line then would send {@code <C-c>} and close the window it just opened.
     */
    private boolean insideWidget(Control focus) {
        for (Control c = focus; c != null; c = c.getParent()) {
            if (c == widget) {
                return true;
            }
        }
        return false;
    }

    /** Stops watching. */
    void dispose() {
        if (!widget.isDisposed()) {
            widget.removeListener(SWT.FocusOut, onFocusOut);
        }
    }
}
