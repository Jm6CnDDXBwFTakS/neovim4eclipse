// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.junit.jupiter.api.Test;

/**
 * An {@code IDocument} seen as the {@code LineSource} the pure {@code util} classes work on. Lines
 * and offsets are 0-based, line lengths leave out the line delimiter, and a document that ends with
 * a delimiter has an empty last line (as {@code IDocument} counts them):
 *
 * <pre>
 *   "ab\r\ncd\n"   line 0 "ab" at 0, line 1 "cd" at 4, line 2 "" at 7
 * </pre>
 */
class DocumentLineSourceTest {

    private final DocumentLineSource source = new DocumentLineSource(new Document("ab\r\ncd\n"));

    @Test
    void countsTheEmptyLineAfterAFinalDelimiter() {
        assertEquals(3, source.lineCount());
        assertEquals(7, source.length());
    }

    @Test
    void lineOffsetsCountEveryDelimiterCharacter() {
        assertEquals(0, source.lineOffset(0));
        assertEquals(4, source.lineOffset(1)); // after "ab\r\n"
        assertEquals(7, source.lineOffset(2));
    }

    @Test
    void lineContentLengthLeavesOutTheDelimiter() {
        assertEquals(2, source.lineContentLength(0));
        assertEquals(2, source.lineContentLength(1));
        assertEquals(0, source.lineContentLength(2));
    }

    @Test
    void textReadsARange() {
        assertEquals("b\r\nc", source.text(1, 4));
    }

    @Test
    void positionsOutsideTheDocumentFailUncheckedWithTheJFaceCause() {
        for (Runnable bad : new Runnable[] {
                () -> source.lineOffset(5),
                () -> source.lineContentLength(5),
                () -> source.text(5, 3)}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, bad::run);
            assertInstanceOf(BadLocationException.class, e.getCause());
        }
    }
}
