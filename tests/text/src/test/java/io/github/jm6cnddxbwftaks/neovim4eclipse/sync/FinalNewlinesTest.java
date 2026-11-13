// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.junit.jupiter.api.Test;

/**
 * {@link FinalNewlines#neovimLineCount}: Neovim's lines for a document, without the empty one
 * Eclipse shows after a final newline, and always the same count a push gives.
 */
class FinalNewlinesTest {

    @Test
    void theEmptyLineAfterAFinalNewlineIsNotNeovims() {
        assertEquals(2, FinalNewlines.neovimLineCount(new Document("alpha\nbeta\n")));
        assertEquals(1, FinalNewlines.neovimLineCount(new Document("alpha\r\n")), "CRLF too");
        assertEquals(2, FinalNewlines.neovimLineCount(new Document("alpha\nbeta")), "no final newline: every line");
        assertEquals(1, FinalNewlines.neovimLineCount(new Document("")), "an empty buffer has one line");
        assertEquals(1, FinalNewlines.neovimLineCount(new Document("\n")));
    }

    /** After {@code o} on the last line of {@code "a"}: {@code "a\n"} is two lines in Neovim. */
    @Test
    void aLineBreakAddedToATextWithoutAFinalNewlineStartsALine() {
        IDocument document = new Document("a");
        FinalNewlines.linesToPush(document); // decides: no final newline
        document.set("a\n");
        assertEquals(2, FinalNewlines.neovimLineCount(document));
    }

    @Test
    void theCountIsTheOneAPushGives() {
        for (String text : new String[] { "", "a", "a\n", "a\nb\n", "a\n\n", "a\r\nb\r\n", "\n\n" }) {
            for (Boolean firstHadOne : new Boolean[] { null, true, false }) {
                IDocument document = new Document(firstHadOne == null ? text : firstHadOne ? "x\n" : "x");
                if (firstHadOne != null) {
                    FinalNewlines.linesToPush(document);
                    document.set(text);
                }
                assertEquals(FinalNewlines.linesForNeovim(document).size(), FinalNewlines.neovimLineCount(document),
                        () -> "\"" + text.replace("\n", "\\n").replace("\r", "\\r") + "\" after " + firstHadOne);
            }
        }
    }
}
