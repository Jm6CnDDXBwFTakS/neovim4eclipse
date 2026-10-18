// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.VisualSelectionGeometry.Range;

/**
 * Corners are Neovim's: 1-based line, 0-based UTF-8 byte column; anchor first, cursor second.
 * Offsets are Eclipse's (UTF-16 chars). {@code r(start, end, caretAtStart)} reads as "selected
 * start..end, caret at the start or the end".
 */
class VisualSelectionGeometryTest {

    private static final StringLineSource TEXT = new StringLineSource("alpha\nbeta\ngamma");

    private static Range v(StringLineSource doc, int aLine, int aCol, int cLine, int cCol) {
        return VisualSelectionGeometry.of(doc, aLine, aCol, cLine, cCol, false);
    }

    private static Range lines(StringLineSource doc, int aLine, int aCol, int cLine, int cCol) {
        return VisualSelectionGeometry.of(doc, aLine, aCol, cLine, cCol, true);
    }

    private static Range r(int start, int end, boolean caretAtStart) {
        return new Range(start, end, caretAtStart);
    }

    @Test
    void characterwiseIncludesTheCharacterUnderTheCursor() {
        // v on "l" of alpha, cursor on "e" of beta: "lpha\nbe"
        assertEquals(r(1, 8, false), v(TEXT, 1, 1, 2, 1));
    }

    @Test
    void afterOTheCaretIsAtTheStart() {
        assertEquals(r(1, 8, true), v(TEXT, 2, 1, 1, 1));
    }

    @Test
    void aSingleCharacterSelectionCoversThatCharacter() {
        assertEquals(r(2, 3, false), v(TEXT, 1, 2, 1, 2));
    }

    @Test
    void linewiseCoversWholeLinesDownward() {
        assertEquals(r(6, 16, false), lines(TEXT, 2, 2, 3, 0)); // "beta\ngamma"
    }

    @Test
    void linewiseUpwardCoversTheSameLines() {
        // V on "gamma", then k: the anchor is below the cursor (the V+k bug)
        assertEquals(r(6, 16, true), lines(TEXT, 3, 0, 2, 2));
    }

    @Test
    void linewiseOnOneLineIgnoresColumns() {
        // the cursor (col 0) is left of the anchor (col 3): whole line, caret at its start
        assertEquals(r(6, 10, true), lines(TEXT, 2, 3, 2, 0));
    }

    @Test
    void byteColumnsBecomeCharOffsets() {
        StringLineSource doc = new StringLineSource("aé b");
        // v on "é" (byte 1) to "b" (byte 4): chars 1..4
        assertEquals(r(1, 4, false), v(doc, 1, 1, 1, 4));
    }

    @Test
    void anEmojiAtTheEndIsSelectedWhole() {
        StringLineSource doc = new StringLineSource("a 😀 b");
        // cursor on the emoji (byte 2): both UTF-16 halves are selected
        assertEquals(r(0, 4, false), v(doc, 1, 0, 1, 2));
    }

    @Test
    void theEndStopsAtTheEndOfTheDocument() {
        assertEquals(r(11, 16, false), v(TEXT, 3, 0, 3, 99));
    }

    @Test
    void aCursorOnAnEmptyLineSelectsItsLineBreak() {
        StringLineSource doc = new StringLineSource("a\n\nb");
        assertEquals(r(0, 3, false), v(doc, 1, 0, 2, 0)); // "a\n" plus the empty line's "\n"
    }

    @Test
    void linesPastTheEndAreClampedToTheLastLine() {
        assertEquals(r(11, 16, false), lines(TEXT, 3, 0, 9, 0));
    }

    @Test
    void windowsLineBreaksAreNotSelectedByLinewise() {
        StringLineSource doc = new StringLineSource("ab\r\ncd\r\n");
        assertEquals(r(0, 6, false), lines(doc, 1, 0, 2, 0)); // "ab\r\ncd", no final \r\n
    }

    @Test
    void offsetOfClampsTheColumnToTheLine() {
        assertEquals(10, VisualSelectionGeometry.offsetOf(TEXT, 2, 42));
        assertEquals(8, VisualSelectionGeometry.offsetOf(TEXT, 2, 2));
    }

    @Test
    void anchorAndCaretAreTheTwoEnds() {
        Range down = r(1, 8, false);
        assertEquals(1, down.anchor());
        assertEquals(8, down.caret());
        Range up = r(1, 8, true);
        assertEquals(8, up.anchor());
        assertEquals(1, up.caret());
    }
}
