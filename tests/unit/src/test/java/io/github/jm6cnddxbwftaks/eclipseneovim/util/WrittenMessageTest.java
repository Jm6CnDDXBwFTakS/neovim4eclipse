// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class WrittenMessageTest {

    @Test
    void aNewFileWithOneLine() {
        assertEquals("\"new.txt\" [New] 1L, 6B written",
                WrittenMessage.of("new.txt", true, "hello\n", StandardCharsets.UTF_8));
    }

    @Test
    void anExistingFileHasNoNewMarker() {
        assertEquals("\"a.txt\" 2L, 4B written", WrittenMessage.of("a.txt", false, "a\nb\n", StandardCharsets.UTF_8));
    }

    @Test
    void aLastLineWithoutALineBreakCountsAndIsMarked() {
        assertEquals("\"x\" [New] [noeol] 1L, 5B written",
                WrittenMessage.of("x", true, "hello", StandardCharsets.UTF_8));
        assertEquals("\"x\" [noeol] 2L, 3B written", WrittenMessage.of("x", false, "a\nb", StandardCharsets.UTF_8));
    }

    @Test
    void anEmptyFileIsZeroLinesAndBytes() {
        assertEquals("\"e\" [New] 0L, 0B written", WrittenMessage.of("e", true, "", StandardCharsets.UTF_8));
    }

    @Test
    void windowsAndOldMacLineBreaksCountOnce() {
        assertEquals(2, WrittenMessage.lineCount("a\r\nb\r\n"));
        assertEquals(2, WrittenMessage.lineCount("a\rb\r"));
        assertEquals(3, WrittenMessage.lineCount("a\r\n\r\nb"));
        assertEquals("\"w\" 1L, 3B written", WrittenMessage.of("w", false, "a\r\n", StandardCharsets.UTF_8));
    }

    @Test
    void bytesAreCountedInTheFilesEncoding() {
        assertEquals("\"u\" 1L, 4B written", WrittenMessage.of("u", false, "ét\n", StandardCharsets.UTF_8));
        assertEquals("\"l\" 1L, 3B written", WrittenMessage.of("l", false, "ét\n", StandardCharsets.ISO_8859_1));
        assertEquals("\"s\" 1L, 5B written", WrittenMessage.of("s", false, "😀\n", StandardCharsets.UTF_8));
    }
}
