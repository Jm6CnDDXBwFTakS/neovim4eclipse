// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NvimKeysTest {

    @Test
    void enterKeys() {
        for (String k : new String[] { "<CR>", "CR", "\r", "\n", "<C-CR>" }) {
            assertTrue(NvimKeys.isEnter(k), k);
        }
        assertFalse(NvimKeys.isEnter("j"));
        assertFalse(NvimKeys.isEnter("<Esc>"));
        assertFalse(NvimKeys.isEnter(null));
    }

    @Test
    void bufferMutatingSingleKeys() {
        for (char c : "pPxXs~.".toCharArray()) {
            assertTrue(NvimKeys.isBufferMutating(String.valueOf(c)), String.valueOf(c));
        }
        for (char c : "jkhldwi".toCharArray()) {
            assertFalse(NvimKeys.isBufferMutating(String.valueOf(c)), String.valueOf(c));
        }
    }

    @Test
    void undoAndRedo() {
        assertTrue(NvimKeys.isBufferMutating("u"));
        assertTrue(NvimKeys.isBufferMutating("<C-r>"));
        assertTrue(NvimKeys.isBufferMutating("<C-R>"));
        assertFalse(NvimKeys.isBufferMutating("<C-w>"));
        assertFalse(NvimKeys.isBufferMutating("<Esc>"));
    }

    @Test
    void nullAndEmpty() {
        assertFalse(NvimKeys.isBufferMutating(null));
        assertFalse(NvimKeys.isBufferMutating(""));
    }

    @Test
    void lettersDigitsAndQuoteAreRecordingRegisters() {
        for (String k : new String[] {"a", "z", "A", "Z", "0", "9", "\""}) {
            assertTrue(NvimKeys.isRecordingRegister(k), k);
        }
        for (String k : new String[] {":", "/", "?", "<Esc>", "", null, "ab"}) {
            assertFalse(NvimKeys.isRecordingRegister(k), String.valueOf(k));
        }
    }

    @Test
    void literalTextEscapesLessThan() {
        assertEquals("a<lt>Esc>b", NvimKeys.escapeLiteral("a<Esc>b"));
        assertEquals("plain", NvimKeys.escapeLiteral("plain"));
    }

    @Test
    void exCommandIsTypedAsSilentCommandLine() {
        assertEquals(":silent %s/a/b/g<CR>", NvimKeys.exCommand("%s/a/b/g"));
        assertEquals(":silent echo <lt>x><CR>", NvimKeys.exCommand("  echo <x>  "));
    }
}
