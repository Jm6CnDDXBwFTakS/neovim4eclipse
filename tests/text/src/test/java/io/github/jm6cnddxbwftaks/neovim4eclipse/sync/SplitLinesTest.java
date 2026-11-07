// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.jface.text.Document;
import org.junit.jupiter.api.Test;

/** {@link SplitLines}: Neovim's line numbers turned into the document's while a line holds a {@code \r}. */
class SplitLinesTest {

    @Test
    void linesBelowASplitLineMoveDownInEclipse() {
        SplitLines split = new SplitLines();
        split.replaced(3, 4, List.of("\ra"), "\n");
        assertEquals(3, split.eclipseLine(3), "the split line itself starts where it was");
        assertEquals(5, split.eclipseLine(4));
        assertEquals(2, split.eclipseLine(2));
    }

    @Test
    void insertedAndDeletedLinesShiftTheSplitLine() {
        SplitLines split = new SplitLines();
        split.replaced(3, 4, List.of("\ra"), "\n");
        split.replaced(0, 0, List.of("new", "lines"), "\n"); // two lines above it
        assertEquals(7, split.eclipseLine(6)); // the split line is Neovim's 5 now
        assertEquals(5, split.eclipseLine(5));
        split.replaced(0, 3, List.of(), "\n"); // three deleted above it
        assertEquals(2, split.eclipseLine(2));
        assertEquals(4, split.eclipseLine(3));
    }

    @Test
    void replacingTheSplitLineWithAPlainOneEndsTheSplit() {
        SplitLines split = new SplitLines();
        split.replaced(1, 2, List.of("x\ry\rz"), "\n");
        assertEquals(4, split.eclipseLine(2), "two breaks: Eclipse's lines 1 to 3");
        split.replaced(1, 2, List.of("xyz"), "\n");
        assertTrue(split.isEmpty());
    }

    @Test
    void aFullSyncReadsEveryLine() {
        SplitLines split = new SplitLines();
        split.replaced(0, 1, List.of("\r"), "\n");
        split.replacedAll(List.of("a", "b\rc", "d"), "\n");
        assertEquals(1, split.eclipseLine(1));
        assertEquals(3, split.eclipseLine(2));
        split.clear();
        assertTrue(split.isEmpty());
    }

    /** The document's count wins: a "\r" typed before a "\n" is a line of its own there. */
    @Test
    void theDocumentsLineCountCorrectsTheBreaks() {
        SplitLines split = new SplitLines();
        split.replaced(1, 2, List.of("a\r"), "\n");
        assertTrue(split.isEmpty(), "by the rule, one line");
        split.correct(1, 1, 2);
        assertEquals(3, split.eclipseLine(2));
        split.correct(1, 1, 1);
        assertTrue(split.isEmpty());
    }

    /** Each count is checked against how a JFace document splits the same text. */
    @Test
    void breaksAreCountedAsTheDocumentCountsThem() {
        assertBreaks("\r\na\rb", "\n", 2);
        assertBreaks("a\r", "\n", 0); // "a\r\n": one line
        assertBreaks("a\r", "\r\n", 1); // "a\r\r\n": "a" and ""
        assertBreaks("a\r", "\r", 1);
        assertBreaks("a\nb", "\n", 1);
        assertBreaks("plain", "\n", 0);
        assertEquals(0, SplitLines.breaksIn(null, "\n"));
    }

    private static void assertBreaks(String line, String delimiter, int expected) {
        assertEquals(expected, SplitLines.breaksIn(line, delimiter), line);
        // "line" then "next", each ended by the delimiter: lines, plus the empty one after the last
        int documentLines = new Document(line + delimiter + "next" + delimiter).getNumberOfLines();
        assertEquals(expected, documentLines - 3, "the document agrees for " + line);
    }
}
