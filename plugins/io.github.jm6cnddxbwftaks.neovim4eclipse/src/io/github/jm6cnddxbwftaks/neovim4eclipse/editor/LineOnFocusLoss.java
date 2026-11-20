// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.BooleanSupplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;

/**
 * Abandons Neovim's open {@code :}, {@code /} or {@code ?} line when the keyboard leaves the
 * widget that typed it for another part of Eclipse, as a click in the text does
 * ({@code NeovimMouse}) and as leaving a field does.
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
 * dialog) counts as another part, so its line is abandoned too: it would be as invisible.
 *
 * <p>UI thread only. Tested in {@code tests/swt} ({@code LineOnFocusLossTest}).
 */
final class LineOnFocusLoss {

    private final Control widget;
    private final BooleanSupplier lineOpen;
    private final Runnable abandon;
    private final Listener onFocusOut = e -> later();

    /**
     * @param widget   the widget keys are typed into (the editor's text, a page's canvas)
     * @param lineOpen whether a line is open now, typed from this widget ({@code NeovimSession.commandLineOpen})
     * @param abandon  abandons the line ({@code <C-c>} to Neovim)
     */
    LineOnFocusLoss(Control widget, BooleanSupplier lineOpen, Runnable abandon) {
        this.widget = widget;
        this.lineOpen = lineOpen;
        this.abandon = abandon;
        widget.addListener(SWT.FocusOut, onFocusOut);
    }

    private void later() {
        Display display = widget.getDisplay();
        display.asyncExec(() -> {
            if (widget.isDisposed() || widget.isFocusControl()) {
                return; // back already, or never really left (a transient focus change)
            }
            if (display.getActiveShell() != null && lineOpen.getAsBoolean()) { // null: another application
                abandon.run();
            }
        });
    }

    /** Stops watching. */
    void dispose() {
        if (!widget.isDisposed()) {
            widget.removeListener(SWT.FocusOut, onFocusOut);
        }
    }
}
