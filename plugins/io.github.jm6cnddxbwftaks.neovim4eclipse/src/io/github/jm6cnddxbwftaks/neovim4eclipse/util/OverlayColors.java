// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * Colors for the overlays drawn on top of the editor (the {@code :} command line and the output
 * popup), derived from the editor's own colors so that they suit any theme, light or dark.
 *
 * <p>Colors are plain {@code 0xRRGGBB} ints here, so this class stays free of SWT (see the package
 * rule in {@code package-info.java}) and can be unit-tested. The editor package turns them into
 * SWT {@code Color}s.
 *
 * <h2>Why blend toward gray?</h2>
 * Mixing a color a little toward mid-gray ({@code 0x808080}) moves a dark background slightly
 * lighter and a light background slightly darker. One rule therefore gives "a bit of contrast" in
 * every theme, without knowing whether the theme is dark.
 *
 * <pre>
 *   background(0x1E1E1E) = 0x242424   (dark theme: a touch lighter)
 *   background(0xFFFFFF) = 0xF7F7F7   (light theme: a touch darker)
 * </pre>
 */
public final class OverlayColors {

    /** Share of mid-gray mixed into the editor background for the overlay background. */
    public static final double BACKGROUND_TINT = 0.06;

    /** Share of the editor foreground mixed into the editor background for the 1-pixel border. */
    public static final double BORDER_STRENGTH = 0.35;

    private static final int MID_GRAY = 0x808080;

    private OverlayColors() {
    }

    /** The overlay background: the editor background moved {@link #BACKGROUND_TINT} toward gray. */
    public static int background(int editorBackground) {
        return blend(editorBackground, MID_GRAY, BACKGROUND_TINT);
    }

    /**
     * The overlay border: part way from the background to the text color, so it is visible in any
     * theme but quieter than the text itself.
     */
    public static int border(int editorBackground, int editorForeground) {
        return blend(editorBackground, editorForeground, BORDER_STRENGTH);
    }

    /**
     * A red for error text that reads well on the editor background, for when the theme defines no
     * error color: lighter on a dark background, deeper on a light one.
     *
     * <pre>
     *   errorText(0x1E1E1E) = 0xF14C4C   (dark theme)
     *   errorText(0xFFFFFF) = 0xCD3131   (light theme)
     * </pre>
     */
    public static int errorText(int editorBackground) {
        return isDark(editorBackground) ? 0xF14C4C : 0xCD3131;
    }

    /** Whether a color is dark: its perceived brightness (ITU-R BT.601 weights) is below half. */
    static boolean isDark(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return 0.299 * r + 0.587 * g + 0.114 * b < 128;
    }

    /**
     * Linear mix of two {@code 0xRRGGBB} colors, channel by channel.
     *
     * @param amount 0 gives {@code from}, 1 gives {@code to}; values outside are clamped
     */
    public static int blend(int from, int to, double amount) {
        double t = Math.max(0, Math.min(1, amount));
        int result = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int a = (from >> shift) & 0xFF;
            int b = (to >> shift) & 0xFF;
            int mixed = (int) Math.round(a + (b - a) * t);
            result |= mixed << shift;
        }
        return result;
    }
}
