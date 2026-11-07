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

    /** Share of the editor foreground mixed into a search match's color for the box around the current match. */
    public static final double SEARCH_BOX_STRENGTH = 0.5;

    /**
     * The color of the box around the search match under the caret (Neovim's {@code CurSearch}):
     * the match color moved halfway toward the text color, so the box shows on the match's
     * background in either theme: darker in a light theme, lighter in a dark one. A box, not a
     * background: it does not sit under the text, so it can be this strong.
     *
     * <pre>
     *   searchBox(0xCECCF7, 0x000000) = 0x67667C   (a light lavender match color, darker)
     *   searchBox(0x3E3E6E, 0xD4D4D4) = 0x8989A1   (dark theme: lighter)
     * </pre>
     */
    public static int searchBox(int matchColor, int editorForeground) {
        return blend(matchColor, editorForeground, SEARCH_BOX_STRENGTH);
    }

    /**
     * The search match color's default for light editors, an amber as Vim users know
     * {@code hlsearch} (255,214,102): the value of the color definition in {@code plugin.xml}.
     */
    public static final int SEARCH_MATCH_LIGHT = 0xFFD666;

    /**
     * The search match color's default for dark editors, a darker amber (110,88,24) that light text
     * stays readable on: the value of Eclipse's dark theme in {@code css/e4-dark_neovim4eclipse.css}.
     */
    public static final int SEARCH_MATCH_DARK = 0x6E5818;

    /**
     * The search match color for an editor: the color from <i>Colors and Fonts</i> ({@code chosen}),
     * but where that is one of the two defaults, the default that suits the editor's background.
     *
     * <h4>Why</h4>
     * Eclipse gives a color definition one value per theme, and only its own dark theme gets the
     * dark amber, from the plug-in's stylesheet. A dark editor can come without it: another dark
     * theme, the user's own dark editor colors on the light theme, or a high-contrast system
     * setting (Windows: <i>High contrast</i>, a black editor). There the light amber would sit under
     * light text. The other way round, a light editor in the dark theme would get dark text on the
     * dark amber. A color the user picked themself stays as it is, unless it is exactly one of the
     * defaults: the registry cannot tell those apart.
     *
     * <pre>
     *   searchMatch(0xFFD666, 0xFFFFFF) = 0xFFD666   (light default, light editor: kept)
     *   searchMatch(0xFFD666, 0x1E1E1E) = 0x6E5818   (light default, dark editor: the dark one)
     *   searchMatch(0x6E5818, 0xFFFFFF) = 0xFFD666   (dark theme, light editor: the light one)
     *   searchMatch(0xAAE6C8, 0x1E1E1E) = 0xAAE6C8   (the user's own green: kept)
     * </pre>
     */
    public static int searchMatch(int chosen, int editorBackground) {
        if (chosen != SEARCH_MATCH_LIGHT && chosen != SEARCH_MATCH_DARK) {
            return chosen;
        }
        return isDark(editorBackground) ? SEARCH_MATCH_DARK : SEARCH_MATCH_LIGHT;
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
