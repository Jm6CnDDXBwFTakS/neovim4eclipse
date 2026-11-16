// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link CellWidth}: how many of Neovim's screen cells a text takes, and walking a line by cells. */
class CellWidthTest {

    @Test
    void latinLettersTakeOneCellEach() {
        assertEquals(1, CellWidth.of('a'));
        assertEquals(5, CellWidth.of("alpha"));
        assertEquals(0, CellWidth.of(""));
    }

    @Test
    void eastAsianCharactersAndEmojiTakeTwo() {
        assertEquals(2, CellWidth.of("日".codePointAt(0)));
        assertEquals(4, CellWidth.of("日本"));
        assertEquals(2, CellWidth.of("한".codePointAt(0)));
        assertEquals(2, CellWidth.of("😀".codePointAt(0)), "one code point, two chars, two cells");
    }

    /** {@code e} followed by a combining acute accent is one cell: the accent joins the letter. */
    @Test
    void aCombiningMarkTakesNone() {
        assertEquals(1, CellWidth.of("é"));
        assertEquals(1, CellWidth.of("é"));
    }

    /** The completed word starts five cells before the cursor: five letters back, whatever came before them. */
    @Test
    void goingBackByCellsFindsTheWordsStart() {
        assertEquals(3, CellWidth.charsBack("日本 alpha", 8, 5));
        assertEquals(0, CellWidth.charsBack("alpha", 5, 5));
        assertEquals(5, CellWidth.charsBack("alpha", 5, 0), "no cells: where it started");
    }

    @Test
    void goingBackOverWideCharactersCountsTwoEach() {
        assertEquals(0, CellWidth.charsBack("日本", 2, 4));
        assertEquals(1, CellWidth.charsBack("日本", 2, 2));
        assertEquals(1, CellWidth.charsBack("日本", 2, 1), "inside a wide character: it is taken whole");
    }

    @Test
    void goingBackStopsAtTheLinesStartAndClampsTheIndex() {
        assertEquals(0, CellWidth.charsBack("al", 2, 9));
        assertEquals(0, CellWidth.charsBack("al", 99, 2), "an index past the end starts at the end");
        assertEquals(0, CellWidth.charsBack("", 0, 3));
    }

    /** A surrogate pair is one character: going back never stops between its two chars. */
    @Test
    void goingBackKeepsSurrogatePairsWhole() {
        assertEquals(1, CellWidth.charsBack("a😀", 3, 2));
    }

    @Test
    void fittingCutsToWholeCharacters() {
        assertEquals("alp", CellWidth.fit("alpha", 3));
        assertEquals("日本", CellWidth.fit("日本語", 5), "the third would need six cells");
        assertEquals("alpha", CellWidth.fit("alpha", 9));
        assertEquals("", CellWidth.fit("日", 1));
    }

    /** A run of characters that take no cell never fills the cells asked for: the cut still ends. */
    @Test
    void fittingStopsInARunOfZeroWidthCharacters() {
        String marks = "a" + "\u0301".repeat(100_000);
        assertTrue(CellWidth.fit(marks, 4).length() <= 4 * 8 + 8);
        assertTrue(CellWidth.fit(marks, 4).startsWith("a\u0301"));
    }
}
