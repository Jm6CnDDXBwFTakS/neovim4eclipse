// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.BlockSelection.LineRange;

import org.junit.jupiter.api.Test;

class BlockSelectionTest {

    private static BlockSelection.Lines lines(String... text) {
        return new BlockSelection.Lines() {
            @Override public int count() { return text.length; }
            @Override public String line(int i) { return text[i]; }
        };
    }

    private static List<LineRange> block(BlockSelection.Lines l, int line1, int col1, int line2, int col2) {
        return BlockSelection.compute(l, line1, col1, line2, col2, false, 4);
    }

    @Test
    void simpleRectangleOverAscii() {
        // cols 1..2 on lines 1..3
        List<LineRange> r = block(lines("abcdef", "ghijkl", "mnopqr"), 1, 1, 3, 2);
        assertEquals(List.of(new LineRange(0, 1, 3), new LineRange(1, 1, 3), new LineRange(2, 1, 3)), r);
    }

    @Test
    void cornersMayBeGivenInEitherOrder() {
        BlockSelection.Lines l = lines("abcdef", "ghijkl", "mnopqr");
        assertEquals(block(l, 1, 1, 3, 4), block(l, 3, 4, 1, 1));
        assertEquals(block(l, 1, 4, 3, 1), block(l, 3, 1, 1, 4), "anti-diagonal corners give the same block");
    }

    @Test
    void singleCharacterBlock() {
        assertEquals(List.of(new LineRange(1, 2, 3)), block(lines("abc", "def"), 2, 2, 2, 2));
    }

    @Test
    void shortLinesAreOmittedAndEmptyLinesToo() {
        List<LineRange> r = block(lines("abcdef", "ab", "", "abcdef"), 1, 3, 4, 4);
        assertEquals(List.of(new LineRange(0, 3, 5), new LineRange(3, 3, 5)), r);
    }

    @Test
    void lineThatEndsInsideTheBlockIsClipped() {
        // The second corner is past the end of "abcd", so it clamps to the cell after the last character:
        // the block is cells 2..4. Line 1 is cut at cell 4 and line 2 ends where its text ends.
        List<LineRange> r = block(lines("abcdef", "abcd"), 1, 2, 2, 5);
        assertEquals(List.of(new LineRange(0, 2, 5), new LineRange(1, 2, 4)), r);
    }

    @Test
    void toEndOfLineExtendsEveryLine() {
        List<LineRange> r = BlockSelection.compute(lines("abcdef", "abcd", "ab", "abcdefgh"), 1, 2, 4, 2, true, 4);
        assertEquals(List.of(new LineRange(0, 2, 6), new LineRange(1, 2, 4), new LineRange(3, 2, 8)), r);
    }

    @Test
    void tabsCountAsCellsToTheNextTabStop() {
        // line 1: "\tx" -> tab covers cells 0..3, 'x' is cell 4. line 2: "abcdefgh".
        // Corners on 'x' (cell 4) and on 'e' (byte col 4, cell 4): the block is cell 4 only.
        List<LineRange> r = block(lines("\tx", "abcdefgh"), 1, 1, 2, 4);
        assertEquals(List.of(new LineRange(0, 1, 2), new LineRange(1, 4, 5)), r);
    }

    @Test
    void aTabOverlappingTheBlockIsIncludedWhole() {
        // Block is cells 2..3 on lines 1-3; the tab on line 2 spans cells 0..3 and overlaps it.
        List<LineRange> r = block(lines("abcdefgh", "\tx", "abcdefgh"), 1, 2, 3, 3);
        assertEquals(List.of(new LineRange(0, 2, 4), new LineRange(1, 0, 1), new LineRange(2, 2, 4)), r);
    }

    @Test
    void tabWidthChangesTheGeometry() {
        BlockSelection.Lines l = lines("\tx", "abcdefghij");
        // 'x' is at cell 8 with tabstop 8, at cell 4 with tabstop 4.
        assertEquals(List.of(new LineRange(0, 1, 2), new LineRange(1, 8, 9)),
                BlockSelection.compute(l, 1, 1, 2, 8, false, 8));
        assertEquals(List.of(new LineRange(0, 1, 2), new LineRange(1, 4, 5)),
                BlockSelection.compute(l, 1, 1, 2, 4, false, 4));
    }

    @Test
    void multiByteCharactersUseByteColumnsForCornersButCellsForGeometry() {
        // "é" is 2 bytes, 1 cell. Corner on byte col 3 is 'b' (index 2, cell 2).
        List<LineRange> r = block(lines("aébc", "123456"), 1, 3, 2, 2);
        assertEquals(List.of(new LineRange(0, 2, 3), new LineRange(1, 2, 3)), r);
    }

    @Test
    void wideCharactersTakeTwoCells() {
        // "日本" = cells 0-1 and 2-3 (3 bytes each). Corner on the second wide char (byte 3) and on
        // line 2 col 3 ('d', cell 3): block is cells 2..3.
        List<LineRange> r = block(lines("日本x", "abcdef"), 1, 3, 2, 3);
        assertEquals(List.of(new LineRange(0, 1, 2), new LineRange(1, 2, 4)), r);
    }

    @Test
    void aWideCharacterPartiallyInsideTheBlockIsIncluded() {
        // Block is cell 1 only; the wide character on line 2 covers cells 0-1 and is included whole.
        List<LineRange> r = block(lines("abc", "日x", "abc"), 1, 1, 3, 1);
        assertEquals(List.of(new LineRange(0, 1, 2), new LineRange(1, 0, 1), new LineRange(2, 1, 2)), r);
    }

    @Test
    void combiningMarksStayWithTheirBaseCharacter() {
        String decomposed = "e\u0301x"; // e + combining acute, then x
        List<LineRange> r = block(lines(decomposed), 1, 0, 1, 0);
        assertEquals(List.of(new LineRange(0, 0, 2)), r);
    }

    @Test
    void surrogatePairsAreNotSplit() {
        String s = "\uD83D\uDE00ab"; // emoji (wide, 2 cells), a, b
        List<LineRange> r = block(lines(s), 1, 0, 1, 0);
        assertEquals(List.of(new LineRange(0, 0, 2)), r);
    }

    @Test
    void outOfRangeLinesAreClamped() {
        assertEquals(List.of(new LineRange(0, 0, 1)), block(lines("abc"), 0, 0, 99, 0));
    }

    @Test
    void emptyDocumentAndZeroTabstopAreSafe() {
        assertEquals(List.of(), BlockSelection.compute(lines(), 1, 0, 1, 0, false, 4));
        assertEquals(List.of(new LineRange(0, 0, 1)), BlockSelection.compute(lines("a"), 1, 0, 1, 0, false, 0));
    }

    @Test
    void cornerPastTheEndOfItsLineStillDefinesAColumn() {
        // Corner 2 is past the end of the empty line 2 (cell 0); the block is then cells 0..2 from line 1.
        List<LineRange> r = block(lines("abcdef", ""), 1, 2, 2, 0);
        assertEquals(List.of(new LineRange(0, 0, 3)), r);
    }
}
