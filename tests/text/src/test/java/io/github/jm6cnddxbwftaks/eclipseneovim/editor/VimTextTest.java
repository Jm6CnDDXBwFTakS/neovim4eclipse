// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.eclipse.jface.text.IDocument;
import org.junit.jupiter.api.Test;

class VimTextTest {

    @Test
    void theCaretMarkerIsRemovedAndGivesTheOffset() {
        VimText t = VimText.parse("al|pha\n\tbeta");
        assertEquals("alpha\n\tbeta", t.document().get());
        assertEquals(2, t.caret());
        assertEquals(0, t.selectionLength());
        assertArrayEquals(new int[] { 1, 2 }, t.caretLineCol());
    }

    @Test
    void withoutMarkersTheCaretIsAtTheStart() {
        VimText t = VimText.parse("alpha");
        assertEquals(0, t.caret());
        assertEquals("alpha", t.text());
    }

    @Test
    void aSelectionPutsTheCaretAtItsEnd() {
        VimText t = VimText.parse("x\n«beta»\n");
        assertEquals("x\nbeta\n", t.text());
        assertEquals(2, t.selectionStart());
        assertEquals(4, t.selectionLength());
        assertEquals(6, t.caret());
    }

    @Test
    void aCaretMarkerInsideASelectionWins() {
        VimText t = VimText.parse("|«beta»"); // dragged to the left
        assertEquals(0, t.caret());
        assertEquals(4, t.selectionLength());
    }

    @Test
    void anotherCaretMarkerLeavesBarsInTheText() {
        VimText t = VimText.parse("a || b#", '#');
        assertEquals("a || b", t.text());
        assertEquals(6, t.caret());
    }

    @Test
    void theCaretMayBeOnALaterLineAfterTabsAndWideCharacters() {
        VimText t = VimText.parse("\tx\naé |ü");
        assertArrayEquals(new int[] { 2, 3 }, t.caretLineCol());
    }

    @Test
    void misplacedMarkersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> VimText.parse("a|b|c"));
        assertThrows(IllegalArgumentException.class, () -> VimText.parse("«ab"));
        assertThrows(IllegalArgumentException.class, () -> VimText.parse("a»b«c"));
    }

    @Test
    void eachDocumentIsFresh() {
        VimText t = VimText.parse("alpha");
        IDocument first = t.document();
        first.set("changed");
        assertEquals("alpha", t.document().get());
    }

    @Test
    void theSampleHasItsTraps() {
        IDocument doc = VimText.parse(VimText.SAMPLE).document();
        assertEquals(7, doc.getNumberOfLines()); // six lines and the empty one after the final break
        assertEquals('\t', VimText.SAMPLE.charAt(VimText.SAMPLE.indexOf('\n') + 1));
        assertEquals(2, "😀".length()); // a surrogate pair: two UTF-16 chars
    }

    @Test
    void aFakeCaretStartsWhereTheMarkersSay() {
        VimText t = VimText.parse("alpha\n«be»ta");
        FakeCaret caret = t.fakeCaret(t.document());
        caret.assertSelectionRange(6, 2);
        caret.assertCaretPosition(2, 2);
    }
}
