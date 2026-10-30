// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * How wide the status bar's labels are, in pixels, under the cap (preference <em>At most this share
 * of the status bar</em>). Pure arithmetic; {@code editor/StatusBarLabels} measures the font and applies it.
 *
 * <h2>Why a cap</h2>
 * Eclipse's status bar (JFace {@code StatusLine.StatusLineLayout}, read from its bytecode 2026-10-29)
 * gives every item the width it asks for and its own message area ("Building workspace", the
 * selection count) what is left. When the items ask for more than there is, the message area gives up
 * width down to a third of the bar, and then the rightmost items, which are the plug-in's, are cut,
 * down to nothing. So the labels ask for no more than is free ({@link #free}) and no more than a share
 * of the bar ({@link #cap}). When they would take more, the last label (the statusline) gives up its
 * width first, then the one before it, each down to its minimum. Example: wanted {@code 120, 70, 420}
 * px, minimums {@code 120, 70, 70}, cap 400: the statusline gets {@code 210}.
 */
public final class StatusBarWidths {

    private StatusBarWidths() {
    }

    /**
     * The cap in pixels: {@code percent} of the bar's width {@code available}. A bar that has no width
     * yet (not laid out) gives no cap, {@link Integer#MAX_VALUE}; the labels are fitted again when it
     * gets one.
     */
    public static int cap(int available, int percent) {
        if (available <= 0) {
            return Integer.MAX_VALUE;
        }
        return (int) ((long) available * Math.max(0, Math.min(100, percent)) / 100);
    }

    /**
     * The width left for the plug-in's labels in a bar {@code barWidth} wide whose other items (Eclipse's
     * own, such as Writable and the cursor position, separators and gaps included) take
     * {@code othersWidth}: what remains after the message area's third. Example: a 738 px bar with 380
     * px of Eclipse's items leaves {@code 738 - 246 - 380 = 112}. {@link Integer#MAX_VALUE} while the
     * bar has no width; at least 0.
     */
    public static int free(int barWidth, int othersWidth) {
        if (barWidth <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, barWidth - barWidth / 3 - othersWidth);
    }

    /**
     * The widths to use: {@code wanted} as it is when its sum fits in {@code cap}, else shrunk from the
     * last part to the first, none below its {@code minimum}. The result may still exceed the cap when
     * the minimums do.
     *
     * @param wanted  each label's width in pixels, in display order
     * @param minimum the least each may get, same order (a minimum above its wanted width counts as
     *                the wanted width)
     * @param cap     the most all may take together ({@link #cap})
     */
    public static int[] fit(int[] wanted, int[] minimum, int cap) {
        int[] widths = wanted.clone();
        long excess = -(long) cap;
        for (int w : widths) {
            excess += w;
        }
        for (int i = widths.length - 1; i >= 0 && excess > 0; i--) {
            int give = (int) Math.min(excess, Math.max(0, widths[i] - minimum[i]));
            widths[i] -= give;
            excess -= give;
        }
        return widths;
    }

    /**
     * Whole characters of average width {@code charWidth} pixels in {@code pixels}: how many columns
     * Neovim may use for the statusline in a label that wide (its {@code nvim_eval_statusline}
     * {@code maxwidth}). At least 1.
     */
    public static int columns(int pixels, double charWidth) {
        if (charWidth <= 0) {
            return 1;
        }
        return Math.max(1, (int) Math.floor(pixels / charWidth));
    }

    /** {@code chars} characters of average width {@code charWidth}, in whole pixels (rounded up). */
    public static int pixels(int chars, double charWidth) {
        return (int) Math.ceil(Math.max(0, chars) * Math.max(0, charWidth));
    }
}
