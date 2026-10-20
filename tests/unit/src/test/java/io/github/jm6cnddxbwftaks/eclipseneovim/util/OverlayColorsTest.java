// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OverlayColorsTest {

    @Test
    void blendEndsAreTheInputs() {
        assertEquals(0x123456, OverlayColors.blend(0x123456, 0xABCDEF, 0));
        assertEquals(0xABCDEF, OverlayColors.blend(0x123456, 0xABCDEF, 1));
    }

    @Test
    void blendIsPerChannelAndClamped() {
        assertEquals(0x808080, OverlayColors.blend(0x000000, 0xFFFFFF, 0.5));
        assertEquals(0xFF0000, OverlayColors.blend(0x000000, 0xFF0000, 7));
        assertEquals(0x000000, OverlayColors.blend(0x000000, 0xFF0000, -1));
    }

    @Test
    void backgroundMovesDarkThemesLighterAndLightThemesDarker() {
        assertEquals(0x242424, OverlayColors.background(0x1E1E1E));
        assertEquals(0xF7F7F7, OverlayColors.background(0xFFFFFF));
    }

    @Test
    void backgroundChangeIsSlight() {
        int bg = 0x2B2B2B;
        int tinted = OverlayColors.background(bg);
        assertTrue(Math.abs((tinted & 0xFF) - (bg & 0xFF)) <= 10, Integer.toHexString(tinted));
    }

    @Test
    void borderLiesBetweenBackgroundAndForeground() {
        int border = OverlayColors.border(0xFFFFFF, 0x000000);
        assertEquals(0xA6A6A6, border);
    }

    @Test
    void errorTextIsLighterOnDarkThemesAndDeeperOnLightOnes() {
        assertEquals(0xF14C4C, OverlayColors.errorText(0x1E1E1E));
        assertEquals(0xCD3131, OverlayColors.errorText(0xFFFFFF));
    }

    @Test
    void darknessFollowsPerceivedBrightness() {
        assertTrue(OverlayColors.isDark(0x2F2F2F));
        assertTrue(OverlayColors.isDark(0x0000FF), "pure blue looks dark");
        assertFalse(OverlayColors.isDark(0xFFFF00), "yellow looks light");
        assertFalse(OverlayColors.isDark(0xF7F7F7));
    }
}
