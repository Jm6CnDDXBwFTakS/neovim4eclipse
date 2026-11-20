// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.Function;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Listener;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.OverlayColors;

/**
 * A border around a control that floats over the editor, such as the {@code :} command line:
 * 1 pixel wide, or wider where the overlay asks for it (the completion list).
 *
 * <h2>Why draw our own border?</h2>
 * SWT's {@code SWT.BORDER} style asks the operating system for its native border, and each platform
 * draws something different: GTK frames a {@code Text} even without the style, while Windows 11
 * draws nothing at all for a borderless {@code Text}. To look the same everywhere, the control is
 * placed inside a {@link Composite} whose background is the border color, with a margin of the border's width
 * left by its {@link FillLayout}. The margin is the border.
 *
 * <pre>
 *   host (the editor's StyledText, or a page editor's GridCanvas)
 *   └── frame: Composite, background = border color, FillLayout margin 1px
 *       └── content: Text / StyledText, background = tinted editor background
 * </pre>
 *
 * <p>Colors follow the editor's colors through {@link OverlayColors}. Since SWT 4.17 a
 * {@link Color} made from RGB values holds no operating-system resource, so it needs no
 * {@code dispose()}.
 *
 * <h2>Following a change of the editor's colors</h2>
 * The editor's colors can change while an overlay is open: a switch between the light and dark
 * theme, the Text Editors preferences, or a TextMate theme set straight on the widget. Each of
 * these repaints the editor, so the frame looks at the editor's colors at each of its paints, as
 * {@code SearchColors} does, and when they differ from the last ones takes new colors (posted, not
 * during the paint). The content's text color follows the editor's.
 *
 * <p>Callers position the frame ({@link #control()}), not the content. Disposing the frame disposes
 * the content with it. UI thread only.
 */
final class OverlayFrame<T extends Control> {

    /** Border width in pixels, unless the overlay asks for another ({@link #OverlayFrame(StyledText, Function, int)}). */
    static final int BORDER = 1;

    /** Gap between an overlay and the editor's left and right edges. */
    static final int SIDE_PADDING = 3;


    private final Canvas host;
    private final Composite frame;
    private final T content;
    /** The editor's colors the overlay's colors were last made from. UI thread. */
    private RGB seenBackground;
    private RGB seenForeground;
    private final Listener hostPaint = event -> onHostPaint();

    /**
     * @param host    the editor's text widget, which the overlay floats over
     * @param factory creates the content control inside the given parent
     */
    OverlayFrame(Canvas host, Function<Composite, T> factory) {
        // NO_FOCUS: the frame is only a border. Composite.setFocus offers the focus to its children
        // first, so without it an editor getting the focus could hand it to the frame of an overlay
        // whose content refuses it.
        this.host = host;
        frame = new Composite(host, SWT.NO_FOCUS);
        FillLayout layout = new FillLayout();
        layout.marginWidth = BORDER;
        layout.marginHeight = BORDER;
        frame.setLayout(layout);

        content = factory.apply(frame);
        content.setFont(host.getFont());
        applyColors();
        host.addListener(SWT.Paint, hostPaint);
        frame.addDisposeListener(event -> {
            if (!host.isDisposed()) {
                host.removeListener(SWT.Paint, hostPaint);
            }
        });
    }

    /**
     * The editor's background, the one overlays and Neovim's {@code 'background'} follow. Example:
     * a dark theme's {@code #2F2F2F}.
     */
    static Color intendedBackground(Canvas host) {
        return host.getBackground();
    }

    /** Border, background and text color from the editor's colors now. */
    private void applyColors() {
        if (frame.isDisposed() || host.isDisposed()) {
            return;
        }
        Color background = intendedBackground(host);
        Color foreground = host.getForeground();
        seenBackground = background.getRGB();
        seenForeground = foreground.getRGB();
        frame.setBackground(rgb(OverlayColors.border(rgbOf(background), rgbOf(foreground))));
        content.setBackground(rgb(OverlayColors.background(rgbOf(background))));
        content.setForeground(foreground);
    }

    /** At the editor's paint: new colors once its background or text color changed (class comment). */
    private void onHostPaint() {
        if (frame.isDisposed()) {
            return;
        }
        RGB background = intendedBackground(host).getRGB();
        RGB foreground = host.getForeground().getRGB();
        if (!background.equals(seenBackground) || !foreground.equals(seenForeground)) {
            seenBackground = background; // one update per change, however many paints come first
            seenForeground = foreground;
            host.getDisplay().asyncExec(this::applyColors);
        }
    }

    /** The control to position and size. */
    Control control() {
        return frame;
    }

    /** The control inside the border. */
    T content() {
        return content;
    }

    /** Height of the frame for content of the given height. */
    static int outerHeight(int contentHeight) {
        return contentHeight + 2 * BORDER;
    }

    /**
     * One line's height in {@code host}, which an overlay is sized by: a text editor's
     * {@code StyledText} line (its font and line spacing), a page's grid cell, or else the font's
     * height. Example: the {@code :} box over the messages page is one grid row high.
     */
    static int lineHeight(Canvas host) {
        if (host instanceof StyledText text) {
            return text.getLineHeight();
        }
        if (host instanceof GridCanvas grid) {
            return grid.cellHeight();
        }
        GC gc = new GC(host);
        try {
            gc.setFont(host.getFont());
            return gc.getFontMetrics().getHeight();
        } finally {
            gc.dispose();
        }
    }

    /**
     * Space left between the bottom edge of the editor and the lowest overlay: about half the height
     * of a one-line command line, so that the overlay stays clear of the horizontal scroll bar.
     */
    static int bottomGap(Canvas host) {
        return Math.max(SIDE_PADDING, outerHeight(OverlayFrame.lineHeight(host)) / 2);
    }

    /**
     * One character's width in {@code host}, as {@link #lineHeight} a line's: a page's grid cell, or
     * the average character of the host's font (a text editor's text font). Example: {@code 5 Ctrl-W >}
     * from the messages page widens its tab group by five of its grid columns.
     */
    static int charWidth(Canvas host) {
        if (host instanceof GridCanvas grid) {
            return Math.max(1, grid.cellWidth());
        }
        GC gc = new GC(host);
        try {
            gc.setFont(host.getFont());
            return Math.max(1, (int) Math.round(gc.getFontMetrics().getAverageCharacterWidth()));
        } finally {
            gc.dispose();
        }
    }

    boolean isDisposed() {
        return frame.isDisposed();
    }

    void dispose() {
        if (!frame.isDisposed()) {
            frame.dispose();
        }
    }

    private static int rgbOf(Color color) {
        return (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
    }

    private static Color rgb(int rgb) {
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }
}
