// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.BooleanSupplier;

import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Listener;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.OverlayColors;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusLineFormat;

/**
 * Neovim's latest message or error, in a line along the bottom of the editor: where Vim shows its
 * messages and where the {@code :} command line opens. Errors ({@code E486: Pattern not found: x},
 * {@code E37: No write since last change}) are drawn in the theme's error color, other messages
 * ({@code 3 lines yanked}) in the editor's text color.
 *
 * <p>Why not the status bar: a message at the far bottom of the window is easy to miss, and the
 * moment it matters (a command that did nothing) is the moment the eyes are on the text.
 *
 * <p>As in Vim, a message stays until the next key; a click in the editor also hides it. The line
 * never takes the focus, so the key that hides it reaches the editor as usual. A message too long
 * for the line ends in "..." and is shown whole in the tooltip. With the preference <em>Flash the
 * editor on errors</em>, an error also tints the editor's background for a moment, like Vim's
 * {@code 'visualbell'}.
 *
 * <p>While the {@code :} box is open the line sits just above it ({@link #makeRoomBelow}), so a
 * message that arrives while the user types a command (a timer's {@code :echo}) does not cover the
 * box; when the box closes the line moves back to the bottom edge.
 *
 * <p>UI thread only.
 */
final class MessageLine {

    /** How long the error flash lasts. */
    static final int FLASH_MS = 150;

    /** Share of the error color mixed into the editor background during the flash. */
    static final double FLASH_STRENGTH = 0.3;

    private final StyledText host;
    private final BooleanSupplier flashOnError;
    private OverlayFrame<CLabel> frame;
    private String text = "";
    private boolean error;
    /** The editor's background while a flash shows; {@code null} when no flash is running. */
    private Color backgroundBeforeFlash;
    /** How many flashes have started; for tests (see {@link #flashCount}). */
    private int flashes;
    /** Space kept free below the line for the {@code :} box; 0 while the box is closed. */
    private int bottomInset;

    /** The next key or click hides the line; Shift, Ctrl and the like on their own are not keys yet. */
    private final Listener hideOnInput = event -> {
        boolean modifierOnly = event.type == SWT.KeyDown && (event.keyCode & SWT.MODIFIER_MASK) != 0
                && event.character == 0;
        if (!modifierOnly) {
            hide();
        }
    };
    private final PaintListener repositionOnPaint = event -> reposition();
    private final ControlListener repositionOnResize = new ControlListener() {
        @Override
        public void controlMoved(ControlEvent e) {
            reposition();
        }

        @Override
        public void controlResized(ControlEvent e) {
            reposition();
        }
    };

    /**
     * @param host         the editor's text widget
     * @param flashOnError asked at each error whether to flash (the preference may change at any time)
     */
    MessageLine(StyledText host, BooleanSupplier flashOnError) {
        this.host = host;
        this.flashOnError = flashOnError;
        host.addListener(SWT.KeyDown, hideOnInput);
        host.addListener(SWT.MouseDown, hideOnInput);
    }

    /**
     * Shows {@code message}, replacing what was shown; a blank message hides the line.
     *
     * @param isError whether it is an error: drawn in the error color, and may flash the editor
     */
    void show(String message, boolean isError) {
        String clean = StatusLineFormat.cleanText(message);
        if (host.isDisposed()) {
            return;
        }
        if (clean.isEmpty()) {
            hide();
            return;
        }
        text = clean;
        error = isError;
        if (frame == null || frame.isDisposed()) {
            frame = new OverlayFrame<>(host, parent -> new CLabel(parent, SWT.LEFT));
            frame.content().addListener(SWT.MouseDown, hideOnInput);
            host.addPaintListener(repositionOnPaint);
            host.addControlListener(repositionOnResize);
        }
        CLabel label = frame.content();
        label.setText(text);
        label.setToolTipText(text);
        label.setForeground(isError ? errorColor() : host.getForeground());
        reposition();
        frame.control().moveAbove(null);
        frame.control().setVisible(true);
        if (isError && flashOnError.getAsBoolean()) {
            flash();
        }
    }

    /**
     * Keeps {@code pixels} free below the line, now and for later messages, for the {@code :} box and
     * its candidate list; 0 puts the line back at the bottom edge. Called by {@link CommandLineBox}.
     */
    void makeRoomBelow(int pixels) {
        bottomInset = Math.max(0, pixels);
        reposition();
    }

    /** Hides the line; the next {@link #show} brings it back. */
    void hide() {
        text = "";
        error = false;
        if (frame == null) {
            return;
        }
        OverlayFrame<CLabel> old = frame;
        frame = null;
        if (!host.isDisposed()) {
            host.removePaintListener(repositionOnPaint);
            host.removeControlListener(repositionOnResize);
        }
        old.dispose(); // disposes the label too
    }

    boolean isShowing() {
        return frame != null && !frame.isDisposed();
    }

    /** The message shown, one line; "" when hidden. */
    String text() {
        return text;
    }

    /** Whether the message shown is an error. */
    boolean isError() {
        return error;
    }

    /** The frame around the line, for tests: its bounds, its color. {@code null} when hidden. */
    OverlayFrame<CLabel> frame() {
        return frame;
    }

    void dispose() {
        hide();
        endFlash();
        if (!host.isDisposed()) {
            host.removeListener(SWT.KeyDown, hideOnInput);
            host.removeListener(SWT.MouseDown, hideOnInput);
        }
    }

    /**
     * One line high, the editor's width, just above its bottom edge, as the command line sits; above
     * the box while it is open ({@link #bottomInset}).
     */
    private void reposition() {
        if (!isShowing() || host.isDisposed()) {
            return;
        }
        Rectangle area = host.getClientArea();
        int width = Math.max(10, area.width - 2 * OverlayFrame.SIDE_PADDING);
        int height = OverlayFrame.outerHeight(
                Math.max(host.getLineHeight(), frame.content().computeSize(SWT.DEFAULT, SWT.DEFAULT).y));
        int x = area.x + OverlayFrame.SIDE_PADDING;
        int y = area.y + area.height - height - OverlayFrame.bottomGap(host) - bottomInset;
        Rectangle current = frame.control().getBounds();
        if (current.x != x || current.y != y || current.width != width || current.height != height) {
            frame.control().setBounds(x, y, width, height);
        }
    }

    /**
     * Tints the editor's background toward the error color for {@link #FLASH_MS}, then puts it back.
     * A flash that is still running is not restarted, so fast repeated errors give one flash.
     */
    private void flash() {
        if (backgroundBeforeFlash != null || host.isDisposed()) {
            return;
        }
        Color before = host.getBackground();
        backgroundBeforeFlash = before;
        flashes++;
        host.setBackground(rgb(OverlayColors.blend(rgbOf(before), rgbOf(errorColor()), FLASH_STRENGTH)));
        host.getDisplay().timerExec(FLASH_MS, this::endFlash);
    }

    private void endFlash() {
        Color before = backgroundBeforeFlash;
        backgroundBeforeFlash = null;
        if (before != null && !host.isDisposed()) {
            host.setBackground(before);
        }
    }

    /** Whether the flash is showing right now; for tests. */
    boolean isFlashing() {
        return backgroundBeforeFlash != null;
    }

    /**
     * How many flashes have started, for tests that run the event loop: a flash lasts only
     * {@link #FLASH_MS}, and a slow machine can start and end it between two looks at
     * {@link #isFlashing()} (on macOS one turn of the event loop may run several timers).
     */
    int flashCount() {
        return flashes;
    }

    /**
     * The theme's error color (the one Eclipse uses for errors in dialogs and the status bar), or a
     * red that suits the editor's background when no theme defines one (outside the workbench).
     */
    private Color errorColor() {
        Color themed = JFaceResources.getColorRegistry().get(JFacePreferences.ERROR_COLOR);
        return themed != null ? themed : rgb(OverlayColors.errorText(rgbOf(host.getBackground())));
    }

    private static int rgbOf(Color color) {
        return (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
    }

    private static Color rgb(int rgb) {
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }
}
