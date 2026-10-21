// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link BufferRefs}: the arguments of {@code :bd 3 other.txt}. */
class BufferRefsTest {

    @Test
    void numbersAreBufferNumbersAndEverythingElseIsAName() {
        assertTrue(BufferRefs.isNumber("3"));
        assertTrue(BufferRefs.isNumber("123456789"));
        assertFalse(BufferRefs.isNumber("1234567890"), "too long for an int: looked up as a name");
        assertFalse(BufferRefs.isNumber("other.txt"));
        assertFalse(BufferRefs.isNumber("3a"));
        assertFalse(BufferRefs.isNumber("-1"));
        assertFalse(BufferRefs.isNumber(""));
    }

    @Test
    void aNameBecomesASingleQuotedVimString() {
        assertEquals("bufnr('other.txt')", BufferRefs.bufnrExpression("other.txt"));
        assertEquals("bufnr('it''s.txt')", BufferRefs.bufnrExpression("it's.txt"));
    }

    @Test
    void whatNeovimPrintsBecomesABufferNumber() {
        assertEquals(4, BufferRefs.parseBufnr("4"));
        assertEquals(4, BufferRefs.parseBufnr("\n4\n"));
        assertEquals(-1, BufferRefs.parseBufnr("-1"));
        assertEquals(-1, BufferRefs.parseBufnr("E15: Invalid expression"));
        assertEquals(-1, BufferRefs.parseBufnr(null));
    }

    @Test
    void theErrorIsVimsForANumberOrAName() {
        assertEquals("E516: No buffers were deleted: bd 99", BufferRefs.notFoundMessage("99"));
        assertEquals("E94: No matching buffer for nope", BufferRefs.notFoundMessage("nope"));
    }
}
