// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.logging.Logger;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WheelScroll;

/**
 * Hands the vertical mouse wheel (and touchpad) in the editor to Neovim in normal and visual mode,
 * so that Neovim's window scrolls and drags its cursor along as {@code Ctrl-E} does, and the editor
 * follows Neovim's top line (docs/design/VIEWPORT.md, decisions D1 and D9). One source of where the
 * view is: the editor does not scroll on its own and then tell Neovim, which would race with keys
 * Neovim is still handling.
 *
 * <p><b>How.</b> A display <em>filter</em> sees the editor widget's {@code SWT.MouseVerticalWheel}
 * before the widget does, as {@link NeovimMouse} sees the buttons, and swallows it
 * ({@code doit = false}, and {@code type = SWT.None} so that no listener gets it); its lines
 * ({@link WheelScroll}) go to Neovim. Example: on Linux one notch is {@code count} −3, so Neovim
 * scrolls three lines down, as Eclipse would have.
 *
 * <p><b>What stays Eclipse's.</b> Insert mode (D6: the caret stays where you type; Esc then sends
 * the editor's view), Eclipse mode, the sideways wheel (D4), and the wheel with a modifier: what
 * Eclipse or the system does with it stays (Shift+wheel scrolls sideways on some systems; Ctrl+wheel
 * zooms in some editors and scrolls in Eclipse's text editor). Neovim's
 * {@code 'mouse'} and {@code 'mousescroll'} are not used: the lines are the system's (D9), and
 * without this the editor's own scroll would drag Neovim's cursor the same way
 * ({@code ViewportFollower}), so turning the mouse off in Neovim would change nothing.
 *
 * <p>Each event is logged at FINE ({@code wheel count=-3 detail=1: 3 lines}), so that a user can
 * send what their touchpad reports (D9: measured only on macOS and a Linux VM). UI thread only.
 */
final class NeovimWheel {

    private static final Logger LOG = Logger.getLogger(NeovimWheel.class.getName());

    private final StyledText widget;
    /** Whether Neovim scrolls for the wheel now (Neovim mode, not insert mode). */
    private final BooleanSupplier forward;
    /** The editor's height in rows, for a wheel set to scroll pages (Windows). */
    private final IntSupplier pageRows;
    /** Receives the lines to scroll, positive down. */
    private final IntConsumer neovim;
    private final Display display;
    private final Listener filter = this::onWheel;

    NeovimWheel(StyledText widget, BooleanSupplier forward, IntSupplier pageRows, IntConsumer neovim) {
        this.widget = widget;
        this.forward = forward;
        this.pageRows = pageRows;
        this.neovim = neovim;
        this.display = widget.getDisplay();
        display.addFilter(SWT.MouseVerticalWheel, filter);
    }

    /** Stops forwarding. UI thread. */
    void dispose() {
        display.removeFilter(SWT.MouseVerticalWheel, filter);
    }

    private void onWheel(Event event) {
        if (event.widget != widget || widget.isDisposed() || (event.stateMask & SWT.MODIFIER_MASK) != 0
                || !forward.getAsBoolean()) {
            return;
        }
        int lines = WheelScroll.lines(event.count, event.detail, pageRows.getAsInt());
        LOG.fine(() -> "wheel count=" + event.count + " detail=" + event.detail + ": " + lines + " lines");
        event.doit = false;
        event.type = SWT.None;
        if (lines != 0) {
            neovim.accept(lines);
        }
    }
}
