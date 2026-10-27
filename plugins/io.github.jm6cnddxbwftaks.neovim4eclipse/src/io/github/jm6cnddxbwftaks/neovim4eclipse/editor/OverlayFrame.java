// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.Function;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

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
 * <p>Callers position the frame ({@link #control()}), not the content. Disposing the frame disposes
 * the content with it. UI thread only.
 */
final class OverlayFrame<T extends Control> {

    /** Border width in pixels. */
    static final int BORDER = 1;

    /** Gap between an overlay and the editor's left and right edges. */
    static final int SIDE_PADDING = 3;

    private final Composite frame;
    private final T content;

    /**
     * @param host    the editor's text widget, which the overlay floats over
     * @param factory creates the content control inside the given parent
     */
    OverlayFrame(StyledText host, Function<Composite, T> factory) {
        // NO_FOCUS: the frame is only a border. Composite.setFocus offers the focus to its children
        // first, so without it an editor getting the focus could hand it to the frame of an overlay
        // whose content refuses it (a message, an output popup shown without the focus).
        frame = new Composite(host, SWT.NO_FOCUS);
        FillLayout layout = new FillLayout();
        layout.marginWidth = BORDER;
        layout.marginHeight = BORDER;
        frame.setLayout(layout);
        frame.setBackground(rgb(OverlayColors.border(rgbOf(host.getBackground()), rgbOf(host.getForeground()))));

        content = factory.apply(frame);
        content.setFont(host.getFont());
        content.setBackground(rgb(OverlayColors.background(rgbOf(host.getBackground()))));
        content.setForeground(host.getForeground());
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
