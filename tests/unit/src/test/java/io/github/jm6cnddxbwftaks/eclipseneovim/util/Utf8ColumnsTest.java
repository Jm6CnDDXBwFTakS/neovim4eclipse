// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class Utf8ColumnsTest {

    // "é" = 2 bytes, "日" = 3 bytes, U+1F600 = 4 bytes (2 UTF-16 chars)
    private static final String MIXED = "aé日\uD83D\uDE00z";

    @Test
    void asciiIsIdentity() {
        assertEquals(3, Utf8Columns.byteToCharIndex("hello", 3));
        assertEquals(3, Utf8Columns.charToByteIndex("hello", 3));
    }

    @Test
    void byteToCharWithMultiByteCharacters() {
        assertEquals(0, Utf8Columns.byteToCharIndex(MIXED, 0)); // before 'a'
        assertEquals(1, Utf8Columns.byteToCharIndex(MIXED, 1)); // before 'é'
        assertEquals(2, Utf8Columns.byteToCharIndex(MIXED, 3)); // before '日'
        assertEquals(3, Utf8Columns.byteToCharIndex(MIXED, 6)); // before the emoji
        assertEquals(5, Utf8Columns.byteToCharIndex(MIXED, 10)); // before 'z' (emoji is 2 chars)
        assertEquals(6, Utf8Columns.byteToCharIndex(MIXED, 11)); // end of line
    }

    @Test
    void charToByteWithMultiByteCharacters() {
        assertEquals(0, Utf8Columns.charToByteIndex(MIXED, 0));
        assertEquals(1, Utf8Columns.charToByteIndex(MIXED, 1));
        assertEquals(3, Utf8Columns.charToByteIndex(MIXED, 2));
        assertEquals(6, Utf8Columns.charToByteIndex(MIXED, 3));
        assertEquals(10, Utf8Columns.charToByteIndex(MIXED, 5));
        assertEquals(11, Utf8Columns.charToByteIndex(MIXED, 6));
    }

    @Test
    void conversionsRoundTripOnCharacterBoundaries() {
        int[] boundaries = { 0, 1, 2, 3, 5, 6 };
        for (int charIndex : boundaries) {
            int bytes = Utf8Columns.charToByteIndex(MIXED, charIndex);
            assertEquals(charIndex, Utf8Columns.byteToCharIndex(MIXED, bytes), "char index " + charIndex);
        }
    }

    @Test
    void byteColumnInsideACharacterMapsToThatCharacter() {
        assertEquals(1, Utf8Columns.byteToCharIndex(MIXED, 2)); // second byte of 'é'
        assertEquals(2, Utf8Columns.byteToCharIndex(MIXED, 4)); // middle of '日'
    }

    @Test
    void outOfRangeValuesClamp() {
        assertEquals(0, Utf8Columns.byteToCharIndex("abc", -5));
        assertEquals(3, Utf8Columns.byteToCharIndex("abc", 99));
        assertEquals(0, Utf8Columns.charToByteIndex("abc", -1));
        assertEquals(3, Utf8Columns.charToByteIndex("abc", 99));
    }

    @Test
    void nullAndEmptyLines() {
        assertEquals(0, Utf8Columns.byteToCharIndex(null, 4));
        assertEquals(0, Utf8Columns.byteToCharIndex("", 4));
        assertEquals(0, Utf8Columns.charToByteIndex(null, 4));
        assertEquals(0, Utf8Columns.charToByteIndex("", 4));
    }
}
