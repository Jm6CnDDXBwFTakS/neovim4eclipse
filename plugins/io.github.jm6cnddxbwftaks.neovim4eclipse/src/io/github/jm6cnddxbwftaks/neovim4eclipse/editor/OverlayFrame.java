// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.Function;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Listener;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.OverlayColors;

/**
 * A 1-pixel border around a control that floats over the editor, such as the {@code :} command line.
 *
 * <h2>Why draw our own border?</h2>
 * SWT's {@code SWT.BORDER} style asks the operating system for its native border, and each platform
 * draws something different: GTK frames a {@code Text} even without the style, while Windows 11
 * draws nothing at all for a borderless {@code Text}. To look the same everywhere, the control is
 * placed inside a {@link Composite} whose background is the border color, with a 1-pixel margin
 * left by its {@link FillLayout}. The margin is the border.
 *
 * <pre>
 *   host (the editor's StyledText)
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
 * during the paint). The content's text color follows only while it is the editor's: an owner that
 * set its own (the message line's error color) keeps it.
 *
 * <p>The error flash ({@code MessageLine}) tints the editor's background for a moment; that tint is
 * not the editor's color. While it shows, the editor's own background is kept on the widget under
 * {@link #BACKGROUND_UNDER_FLASH}, and {@link #intendedBackground} reads it from there. So an
 * overlay opened during a flash (an error, then {@code :} at once) takes the editor's colors, not
 * the tint's, and an overlay already open does not flash along.
 *
 * <p>Callers position the frame ({@link #control()}), not the content. Disposing the frame disposes
 * the content with it. UI thread only.
 */
final class OverlayFrame<T extends Control> {

    /** Border width in pixels. */
    static final int BORDER = 1;

    /** Gap between an overlay and the editor's left and right edges. */
    static final int SIDE_PADDING = 3;

    /**
     * Key of the widget data ({@link StyledText#setData(String, Object)}) that holds the editor's own
     * background while the error flash tints it (see the class comment); absent otherwise.
     */
    static final String BACKGROUND_UNDER_FLASH = OverlayFrame.class.getName() + ".backgroundUnderFlash";

    private final StyledText host;
    private final Composite frame;
    private final T content;
    /** The editor's colors the overlay's colors were last made from. UI thread. */
    private RGB seenBackground;
    private RGB seenForeground;
    /** The text color last given to the content: while the content still has it, it follows the editor's. */
    private RGB appliedForeground;
    private final Listener hostPaint = event -> onHostPaint();

    /**
     * @param host    the editor's text widget, which the overlay floats over
     * @param factory creates the content control inside the given parent
     */
    OverlayFrame(StyledText host, Function<Composite, T> factory) {
        // NO_FOCUS: the frame is only a border. Composite.setFocus offers the focus to its children
        // first, so without it an editor getting the focus could hand it to the frame of an overlay
        // whose content refuses it (a message, an output popup shown without the focus).
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
     * The editor's background as its owner set it: during the error flash the one under the tint
     * ({@link #BACKGROUND_UNDER_FLASH}), otherwise the widget's.
     */
    static Color intendedBackground(StyledText host) {
        return host.getData(BACKGROUND_UNDER_FLASH) instanceof Color own ? own : host.getBackground();
    }

    /** Border, background and (while it is the editor's) text color from the editor's colors now. */
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
        if (appliedForeground == null || appliedForeground.equals(content.getForeground().getRGB())) {
            content.setForeground(foreground);
            appliedForeground = foreground.getRGB();
        }
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
     * Space left between the bottom edge of the editor and the lowest overlay: about half the height
     * of a one-line command line, so that the overlay stays clear of the horizontal scroll bar.
     */
    static int bottomGap(StyledText host) {
        return Math.max(SIDE_PADDING, outerHeight(host.getLineHeight()) / 2);
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
