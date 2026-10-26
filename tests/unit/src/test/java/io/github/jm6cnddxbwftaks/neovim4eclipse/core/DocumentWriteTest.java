// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** {@link DocumentWrite}: the write request's {@code ++p} and the answer sent back to Neovim. */
class DocumentWriteTest {

    @Test
    void plusPlusPInCmdargCreatesFolders() {
        assertTrue(DocumentWrite.createsFolders(" ++p"));
        assertTrue(DocumentWrite.createsFolders(" ++enc=latin1 ++p"));
        assertFalse(DocumentWrite.createsFolders(" ++enc=latin1"));
        assertFalse(DocumentWrite.createsFolders(""));
        assertFalse(DocumentWrite.createsFolders(null));
    }

    @Test
    void theAnswerIsErrorThenMessage() {
        assertEquals(List.of("", "\"a.txt\" 1L, 2B written"), DocumentWrite.Result.saved("\"a.txt\" 1L, 2B written").toRpc());
        assertEquals(List.of("E212: read-only", ""), DocumentWrite.Result.failed("E212: read-only").toRpc());
        assertTrue(DocumentWrite.Result.saved(null).ok());
        assertFalse(DocumentWrite.Result.failed("x").ok());
    }
}
