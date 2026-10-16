// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.InsertReplay.Plan;

import org.junit.jupiter.api.Test;

class InsertReplayTest {

    // ---- insertedText ----

    @Test
    void plainInsertionAtTheBase() {
        assertEquals(" add", InsertReplay.insertedText("one\ntwo\n", "one add\ntwo\n", 3));
    }

    @Test
    void insertionAtStartAndEnd() {
        assertEquals("x", InsertReplay.insertedText("abc", "xabc", 0));
        assertEquals("x", InsertReplay.insertedText("abc", "abcx", 3));
    }

    @Test
    void insertedTextEqualToTheFollowingTextIsStillFoundAtTheBase() {
        // Inserting "ab" before an existing "ab": a prefix/suffix diff alone would be ambiguous.
        assertEquals("ab", InsertReplay.insertedText("abc", "ababc", 0));
    }

    @Test
    void multiLineInsertion() {
        assertEquals("1\n2\n3", InsertReplay.insertedText("ab", "a1\n2\n3b", 1));
    }

    @Test
    void notAPlainInsertion() {
        assertNull(InsertReplay.insertedText("abc", "abc", 1), "nothing typed");
        assertNull(InsertReplay.insertedText("abc", "ac", 1), "text was deleted");
        assertNull(InsertReplay.insertedText("abc", "xabcx", 0), "edits in two places");
        assertNull(InsertReplay.insertedText("abc", "axbc", 0), "inserted elsewhere than the base");
        assertNull(InsertReplay.insertedText("abc", "abcd", 9), "base out of range");
        assertNull(InsertReplay.insertedText(null, "a", 0));
    }

    // ---- toKeyChunks ----

    @Test
    void plainTextPassesThrough() {
        assertEquals(List.of("hello world"), InsertReplay.toKeyChunks("hello world"));
    }

    @Test
    void specialCharactersBecomeKeyNotation() {
        assertEquals(List.of("a<lt>b<CR>c<Tab>d"), InsertReplay.toKeyChunks("a<b\nc\td"));
        assertEquals(List.of("<lt>Esc>"), InsertReplay.toKeyChunks("<Esc>"), "text that looks like a key stays text");
    }

    @Test
    void allLineBreakStylesBecomeOneEnter() {
        assertEquals(List.of("a<CR>b<CR>c<CR>d"), InsertReplay.toKeyChunks("a\r\nb\nc\rd"));
    }

    @Test
    void otherControlCharactersAreDropped() {
        assertEquals(List.of("ab"), InsertReplay.toKeyChunks("a\u0001\u0007b\u007F"));
        assertEquals(List.of(), InsertReplay.toKeyChunks("\u0001"));
    }

    @Test
    void unicodeIsKept() {
        assertEquals(List.of("é日\uD83D\uDE00"), InsertReplay.toKeyChunks("é日\uD83D\uDE00"));
    }

    @Test
    void longTextIsSplitIntoSmallChunksWithoutLosingAnything() {
        String text = "x".repeat(InsertReplay.CHUNK_CHARS * 2 + 5);
        List<String> chunks = InsertReplay.toKeyChunks(text);
        assertEquals(3, chunks.size());
        assertEquals(text, String.join("", chunks));
        assertEquals(InsertReplay.CHUNK_CHARS, chunks.get(0).length());
    }

    @Test
    void aKeyNotationTokenIsNeverSplitAcrossChunks() {
        String text = "<".repeat(InsertReplay.CHUNK_CHARS + 3);
        for (String chunk : InsertReplay.toKeyChunks(text)) {
            assertEquals(0, chunk.length() % 4, "each '<' becomes the 4-character token <lt>");
        }
    }

    // ---- cursorAfterEsc ----

    @Test
    void escMovesTheCursorOneCharacterLeft() {
        assertEquals(6, InsertReplay.cursorAfterEsc("one add", 7));
    }

    @Test
    void escAtTheStartOfALineDoesNotMove() {
        assertEquals(4, InsertReplay.cursorAfterEsc("abc\ndef", 4));
        assertEquals(0, InsertReplay.cursorAfterEsc("abc", 0));
    }

    @Test
    void escStepsOverAWholeSurrogatePair() {
        String s = "a\uD83D\uDE00"; // caret after the emoji
        assertEquals(1, InsertReplay.cursorAfterEsc(s, 3));
    }

    // ---- lineAndByteCol ----

    @Test
    void positionsOnTheFirstAndLaterLines() {
        assertEquals(List.of(1, 3), asList(InsertReplay.lineAndByteCol("one\ntwo", 3)));
        assertEquals(List.of(2, 0), asList(InsertReplay.lineAndByteCol("one\ntwo", 4)));
        assertEquals(List.of(2, 2), asList(InsertReplay.lineAndByteCol("one\ntwo", 6)));
    }

    @Test
    void crLfCountsAsOneLineBreak() {
        assertEquals(List.of(2, 1), asList(InsertReplay.lineAndByteCol("one\r\ntwo", 6)));
        assertEquals(List.of(2, 0), asList(InsertReplay.lineAndByteCol("one\r\ntwo", 5)));
    }

    @Test
    void columnsAreUtf8Bytes() {
        // "é" is 2 bytes, "日" is 3 bytes.
        assertEquals(List.of(1, 2), asList(InsertReplay.lineAndByteCol("é日x", 1)));
        assertEquals(List.of(1, 5), asList(InsertReplay.lineAndByteCol("é日x", 2)));
    }

    // ---- plan ----

    @Test
    void theExampleFromTheRequest() {
        // "Aadd something to end of line<Esc>" typed on line 1 of "one\ntwo\n"
        Plan plan = InsertReplay.plan("one\ntwo\n", "one add\ntwo\n", 3, 7);
        assertEquals(List.of(" add"), plan.keyChunks());
        assertEquals(1, plan.baseLine1Based());
        assertEquals(3, plan.baseCol0Based());
        assertEquals(1, plan.finalLine1Based());
        assertEquals(6, plan.finalCol0Based());
    }

    @Test
    void planForAMultiLineInsertion() {
        Plan plan = InsertReplay.plan("ab", "a1\n2b", 1, 4);
        assertEquals(List.of("1<CR>2"), plan.keyChunks());
        assertEquals(1, plan.baseLine1Based());
        assertEquals(1, plan.baseCol0Based());
        assertEquals(2, plan.finalLine1Based());
        assertEquals(0, plan.finalCol0Based(), "caret after '2' is column 1, Esc moves to column 0");
    }

    @Test
    void noPlanWhenNothingWasTypedOrItIsNotReplayable() {
        assertNull(InsertReplay.plan("abc", "abc", 1, 1));
        assertNull(InsertReplay.plan("abc", "ac", 1, 1));
        assertNull(InsertReplay.plan("abc", "a" + "x".repeat(InsertReplay.MAX_REPLAY_CHARS + 1) + "bc", 1, 5));
        assertNull(InsertReplay.plan("abc", "a\u0001bc", 1, 2), "only an unprintable character was inserted");
    }

    @Test
    void planDoesNotMutateItsInputsAndChunksAreImmutableEnough() {
        Plan plan = InsertReplay.plan("a", "ab", 1, 2);
        assertTrue(plan.keyChunks().size() == 1);
    }

    private static List<Integer> asList(int[] pair) {
        return List.of(pair[0], pair[1]);
    }
}
