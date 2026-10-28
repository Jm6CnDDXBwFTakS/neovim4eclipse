// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

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
    void theRequestNamesTheKindTheFileAndTheLines() {
        assertEquals(DocumentWrite.Request.own(true), DocumentWrite.Request.of(" ++p", "", "", 0, 0, false));
        assertEquals(DocumentWrite.Request.own(false), DocumentWrite.Request.of("", null, null, 0, 0, false),
                "a plain save sends none of them");
        // The file in the system's form (on Windows \ws\p\b.txt): see theFileIsInTheSystemsForm.
        assertEquals(new DocumentWrite.Request(DocumentWrite.Kind.COPY, Path.of("/ws/p/b.txt").toString(), 0, 0, true, false),
                DocumentWrite.Request.of("", "copy", "/ws/p/b.txt", 0, 0, true));
        assertEquals(new DocumentWrite.Request(DocumentWrite.Kind.LINES, Path.of("/ws/p/part.txt").toString(), 5, 10, false, false),
                DocumentWrite.Request.of("", "lines", "/ws/p/part.txt", 5, 10, false));
        assertEquals(DocumentWrite.Kind.APPEND, DocumentWrite.Kind.named("append"));
        assertEquals(DocumentWrite.Kind.SAVE_AS, DocumentWrite.Kind.named("saveas"));
        assertEquals(DocumentWrite.Kind.OWN, DocumentWrite.Kind.named("unknown"), "an unknown kind is a save, never a copy");
    }

    /**
     * Neovim on Windows names the written file with forward slashes ({@code C:/ws/p/b.txt}); Eclipse's
     * paths have backslashes, and the two must compare equal (the editor's own file, an open editor).
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theFileIsInTheSystemsForm() {
        assertEquals("C:\\ws\\p\\b.txt", DocumentWrite.Request.of("", "copy", "C:/ws/p/b.txt", 0, 0, false).target());
    }

    @Test
    void theAnswerIsErrorThenMessage() {
        assertEquals(List.of("", "\"a.txt\" 1L, 2B written"), DocumentWrite.Result.saved("\"a.txt\" 1L, 2B written").toRpc());
        assertEquals(List.of("E212: read-only", ""), DocumentWrite.Result.failed("E212: read-only").toRpc());
        assertTrue(DocumentWrite.Result.saved(null).ok());
        assertFalse(DocumentWrite.Result.failed("x").ok());
    }
}
