// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.BlockSelection.ByteRange;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.BlockSelection.LineRange;

import org.junit.jupiter.api.Test;

/**
 * Turning Neovim's block lines into document characters. Each case uses what Neovim 0.12.6's
 * {@code getregionpos()} returned for that text and block (experiment X5 of the cleanup plan); the
 * screen-cell arithmetic behind those numbers is Neovim's and is not tested here.
 */
class BlockSelectionTest {

    private static BlockSelection.Lines lines(String... text) {
        return new BlockSelection.Lines() {
            @Override public int count() { return text.length; }
            @Override public String line(int i) { return text[i]; }
        };
    }

    private static List<LineRange> compute(BlockSelection.Lines lines, ByteRange... ranges) {
        return BlockSelection.compute(lines, List.of(ranges));
    }

    @Test
    void asciiBytesAreCharacters() {
        // Ctrl-V j l from the second column: bytes 2..3 of each line.
        assertEquals(List.of(new LineRange(0, 1, 3), new LineRange(1, 1, 3)),
                compute(lines("abcdef", "ghijkl"), new ByteRange(1, 2, 3), new ByteRange(2, 2, 3)));
    }

    /** {@code "a\tbcdef"}, block over cells 0..4: the tab is only partly inside and is included whole. */
    @Test
    void aTabPartlyInsideIsIncludedWhole() {
        assertEquals(List.of(new LineRange(0, 0, 2)), compute(lines("a\tbcdef"), new ByteRange(1, 1, 2)));
    }

    /** {@code "ab\tcdef"}, block over cells 4..5, inside the tab: the tab alone. */
    @Test
    void aBlockInsideATabCoversTheTab() {
        assertEquals(List.of(new LineRange(0, 2, 3)), compute(lines("ab\tcdef"), new ByteRange(1, 3, 3)));
    }

    /** {@code "aあいうb"} over cells 1..2: Neovim's last byte is あ's last (byte 4); あ is one char. */
    @Test
    void aWideCharacterIsOneCharacterOfThreeBytes() {
        assertEquals(List.of(new LineRange(0, 1, 2)), compute(lines("aあいうb"), new ByteRange(1, 2, 4)));
    }

    /**
     * {@code "aあいうb"} over cells 2..3, which split あ and い: Neovim points at each partly covered
     * character's first byte (2 and 5), and both are included whole.
     */
    @Test
    void partlyCoveredWideCharactersAtBothEndsAreIncluded() {
        assertEquals(List.of(new LineRange(0, 1, 3)), compute(lines("aあいうb"), new ByteRange(1, 2, 5)));
    }

    /** {@code $}: each line to its own end. */
    @Test
    void dollarRangesEndAtEachLinesEnd() {
        assertEquals(List.of(new LineRange(0, 1, 6), new LineRange(1, 1, 2), new LineRange(2, 1, 10)),
                compute(lines("abcdef", "ab", "abcdefghij"),
                        new ByteRange(1, 2, 6), new ByteRange(2, 2, 2), new ByteRange(3, 2, 10)));
    }

    /** A line that does not reach the block starts past its end ({@code "a"}: byte 2; {@code ""}: byte 1). */
    @Test
    void shortAndEmptyLinesAreLeftOut() {
        assertEquals(List.of(new LineRange(0, 3, 5)),
                compute(lines("abcdef", "a", ""), new ByteRange(1, 4, 5), new ByteRange(2, 2, 2), new ByteRange(3, 1, 1)));
    }

    /** Neovim's last byte is the combining mark's, so the mark stays with its base character. */
    @Test
    void combiningMarksStayWithTheirBaseCharacter() {
        assertEquals(List.of(new LineRange(0, 1, 3)), compute(lines("aéb"), new ByteRange(1, 2, 4)));
    }

    /** {@code "a😀b"}: the emoji is four bytes and two UTF-16 chars, never split. */
    @Test
    void surrogatePairsAreNotSplit() {
        assertEquals(List.of(new LineRange(0, 1, 3)), compute(lines("a😀b"), new ByteRange(1, 2, 5)));
    }

    /** The document can lag Neovim for a moment; a line it does not have yet is left out, not an error. */
    @Test
    void linesTheDocumentDoesNotHaveAreLeftOut() {
        assertEquals(List.of(new LineRange(0, 0, 1)),
                compute(lines("abc"), new ByteRange(0, 1, 1), new ByteRange(1, 1, 1), new ByteRange(2, 1, 1)));
    }

    @Test
    void anEndBeforeTheStartOrPastTheLineIsSafe() {
        assertEquals(List.of(), compute(lines("abc"), new ByteRange(1, 3, 2)));
        assertEquals(List.of(new LineRange(0, 1, 3)), compute(lines("abc"), new ByteRange(1, 2, 99)));
    }
}
