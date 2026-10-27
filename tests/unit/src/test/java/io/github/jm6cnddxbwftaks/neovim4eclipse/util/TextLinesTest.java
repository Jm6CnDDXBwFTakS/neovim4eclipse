// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link TextLines}: the lines {@code :5,10w part.txt} writes. */
class TextLinesTest {

    private static final String TEXT = "a\nb\r\nc\nd";

    @Test
    void eachLineKeepsItsOwnBreak() {
        assertEquals("b\r\nc\n", TextLines.of(TEXT, 2, 3));
        assertEquals("a\n", TextLines.of(TEXT, 1, 1));
        assertEquals(TEXT, TextLines.of(TEXT, 1, 4));
    }

    @Test
    void theLastLineWithoutABreakStaysWithout() {
        assertEquals("d", TextLines.of(TEXT, 4, 4));
        assertEquals("c\nd", TextLines.of(TEXT, 3, 4));
    }

    @Test
    void aLoneCarriageReturnEndsALine() {
        assertEquals("y\r", TextLines.of("x\ry\rz", 2, 2));
    }

    @Test
    void linesPastTheEndAreLeftOut() {
        assertEquals("", TextLines.of("a\nb\n", 3, 5), "a final break ends line 2; there is no line 3");
        assertEquals("b\n", TextLines.of("a\nb\n", 2, 9));
        assertEquals("", TextLines.of("", 1, 1));
    }

    @Test
    void aRangeMustBeInOrder() {
        assertThrows(IllegalArgumentException.class, () -> TextLines.of(TEXT, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> TextLines.of(TEXT, 3, 2));
    }
}
