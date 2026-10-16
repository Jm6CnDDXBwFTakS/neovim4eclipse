// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Random;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.LineRangeEdit.Edit;

import org.junit.jupiter.api.Test;

class TextDiffTest {

    private static String apply(String text, Edit e) {
        return e == null ? text : text.substring(0, e.offset()) + e.text() + text.substring(e.offset() + e.length());
    }

    @Test
    void identicalTextsNeedNoEdit() {
        assertNull(TextDiff.minimalEdit("abc", "abc"));
        assertNull(TextDiff.minimalEdit(null, ""));
    }

    @Test
    void changeInTheMiddleTouchesOnlyThatRegion() {
        Edit e = TextDiff.minimalEdit("hello world", "hello brave world");
        assertEquals(new Edit(6, 0, "brave "), e);
    }

    @Test
    void replacementDeletionAndInsertion() {
        assertEquals(new Edit(1, 1, "X"), TextDiff.minimalEdit("abc", "aXc"));
        assertEquals(new Edit(1, 1, ""), TextDiff.minimalEdit("abc", "ac"));
        assertEquals(new Edit(3, 0, "d"), TextDiff.minimalEdit("abc", "abcd"));
        assertEquals(new Edit(0, 0, "x"), TextDiff.minimalEdit("abc", "xabc"));
    }

    @Test
    void emptyToTextAndBack() {
        assertEquals(new Edit(0, 0, "abc"), TextDiff.minimalEdit("", "abc"));
        assertEquals(new Edit(0, 3, ""), TextDiff.minimalEdit("abc", ""));
    }

    @Test
    void repeatedCharactersDoNotOverlapPrefixAndSuffix() {
        assertEquals("aaaa", apply("aaa", TextDiff.minimalEdit("aaa", "aaaa")));
        assertEquals("aa", apply("aaa", TextDiff.minimalEdit("aaa", "aa")));
    }

    @Test
    void neverSplitsASurrogatePair() {
        // U+1F600 and U+1F601 share their high surrogate; the diff must not cut between halves.
        String a = "x\uD83D\uDE00y";
        String b = "x\uD83D\uDE01y";
        Edit e = TextDiff.minimalEdit(a, b);
        assertEquals(b, apply(a, e));
        assertEquals(1, e.offset());
        assertEquals(2, e.length());
    }

    @Test
    void randomTextsAlwaysRoundTrip() {
        Random random = new Random(7);
        String alphabet = "ab\n\uD83D\uDE00\uD83D\uDE01";
        for (int i = 0; i < 2000; i++) {
            String a = random(random, alphabet);
            String b = random(random, alphabet);
            assertEquals(b, apply(a, TextDiff.minimalEdit(a, b)), "a=" + a + " b=" + b);
        }
    }

    private static String random(Random random, String alphabet) {
        // Build from whole code points so inputs are valid UTF-16.
        int[] cps = alphabet.codePoints().toArray();
        StringBuilder sb = new StringBuilder();
        int n = random.nextInt(8);
        for (int i = 0; i < n; i++) {
            sb.appendCodePoint(cps[random.nextInt(cps.length)]);
        }
        return sb.toString();
    }
}
