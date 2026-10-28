// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.util.WheelScroll.SCROLL_LINE;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.util.WheelScroll.SCROLL_PAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * {@link WheelScroll}: SWT's wheel events to lines, with the sequences measured by
 * {@code docs/design/probes/WheelProbe.java} as cases (VIEWPORT.md, X10 and X11).
 */
class WheelScrollTest {

    @Test
    void aMacWheelNotchIsOneLineDown() {
        assertEquals(1, WheelScroll.lines(-1, SCROLL_LINE, 40), "X10: count -1, 13 px of 13-px lines");
    }

    @Test
    void aMacTrackpadFlickScrollsAsFarAsEclipseWould() {
        // X10, phase 3: the momentum's counts rise to -13 and fall back to -1.
        int[] counts = { -1, -3, -6, -9, -13, -13, -11, -8, -5, -3, -2, -1 };
        int lines = IntStream.of(counts).map(c -> WheelScroll.lines(c, SCROLL_LINE, 40)).sum();
        assertEquals(75, lines, "the sum of the counts, no more");
    }

    @Test
    void aLinuxEventIsThreeLinesEitherWay() {
        assertEquals(3, WheelScroll.lines(-3, SCROLL_LINE, 40), "X11: count -3, 78 px of 26-px lines");
        assertEquals(-3, WheelScroll.lines(3, SCROLL_LINE, 40), "up");
    }

    @Test
    void aPageIsTheEditorLessTwoLinesAsCtrlF() {
        assertEquals(38, WheelScroll.lines(-1, SCROLL_PAGE, 40));
        assertEquals(-76, WheelScroll.lines(2, SCROLL_PAGE, 40));
        assertEquals(1, WheelScroll.lines(-1, SCROLL_PAGE, 2), "never less than a line");
    }

    @Test
    void nothingIsNothing() {
        assertEquals(0, WheelScroll.lines(0, SCROLL_LINE, 40));
    }
}
