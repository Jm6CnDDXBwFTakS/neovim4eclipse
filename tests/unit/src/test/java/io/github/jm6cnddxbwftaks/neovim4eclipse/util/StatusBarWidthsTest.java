// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** {@link StatusBarWidths}: the labels' widths under the cap. */
class StatusBarWidthsTest {

    @Test
    void theCapIsAShareOfTheBar() {
        assertEquals(400, StatusBarWidths.cap(1000, 40));
        assertEquals(Integer.MAX_VALUE, StatusBarWidths.cap(0, 40), "not laid out yet: no cap");
        assertEquals(1000, StatusBarWidths.cap(1000, 150), "at most the whole bar");
        assertEquals(0, StatusBarWidths.cap(1000, -5));
    }

    /** The class comment's example: the message area keeps a third, Eclipse's items their width. */
    @Test
    void whatIsFreeAfterTheMessageAreaAndEclipsesItems() {
        assertEquals(112, StatusBarWidths.free(738, 380));
        assertEquals(0, StatusBarWidths.free(738, 600), "nothing left");
        assertEquals(Integer.MAX_VALUE, StatusBarWidths.free(0, 380), "not laid out yet");
    }

    @Test
    void widthsThatFitAreKept() {
        assertArrayEquals(new int[] { 120, 70, 200 }, StatusBarWidths.fit(new int[] { 120, 70, 200 }, new int[] { 120, 70, 70 }, 400));
    }

    /** The class comment's example: the statusline gives up its width first. */
    @Test
    void theLastLabelShrinksFirst() {
        assertArrayEquals(new int[] { 120, 70, 210 }, StatusBarWidths.fit(new int[] { 120, 70, 420 }, new int[] { 120, 70, 70 }, 400));
    }

    @Test
    void thenTheOneBeforeItEachNoLowerThanItsMinimum() {
        assertArrayEquals(new int[] { 120, 40, 70 }, StatusBarWidths.fit(new int[] { 120, 70, 420 }, new int[] { 120, 40, 70 }, 230));
        assertArrayEquals(new int[] { 120, 40, 70 }, StatusBarWidths.fit(new int[] { 120, 70, 420 }, new int[] { 120, 40, 70 }, 10),
                "the minimums may exceed the cap");
    }

    @Test
    void charactersAndPixels() {
        assertEquals(420, StatusBarWidths.pixels(60, 7.0));
        assertEquals(8, StatusBarWidths.pixels(1, 7.2), "rounded up");
        assertEquals(60, StatusBarWidths.columns(420, 7.0));
        assertEquals(58, StatusBarWidths.columns(420, 7.2), "whole characters only");
        assertEquals(1, StatusBarWidths.columns(3, 7.0), "at least one");
        assertEquals(1, StatusBarWidths.columns(420, 0));
    }
}
