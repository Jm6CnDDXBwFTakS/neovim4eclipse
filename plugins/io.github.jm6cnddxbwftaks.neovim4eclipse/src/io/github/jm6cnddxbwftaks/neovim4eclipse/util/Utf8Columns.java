// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * Converts between Neovim columns and Java string indices.
 *
 * <p>Neovim reports cursor and visual-selection columns as <strong>UTF-8 byte offsets</strong>;
 * Eclipse documents use <strong>UTF-16 code units</strong>. For ASCII they coincide, but for text
 * such as {@code "héllo"} or {@code "日本語"} the caret would land in the wrong place if the two
 * were used interchangeably. All conversions between the two worlds go through this class.
 */
public final class Utf8Columns {

    private Utf8Columns() {
    }

    /**
     * Neovim byte column to Java char index within one line.
     *
     * <p>A byte column that falls in the middle of a multi-byte character maps to that character.
     * Values past the end clamp to {@code line.length()}; negatives clamp to 0.
     */
    public static int byteToCharIndex(String line, int byteCol) {
        if (byteCol <= 0 || line == null || line.isEmpty()) {
            return 0;
        }
        int bytes = 0;
        int i = 0;
        while (i < line.length()) {
            int cp = line.codePointAt(i);
            int width = utf8Length(cp);
            if (bytes + width > byteCol) {
                return i;
            }
            bytes += width;
            i += Character.charCount(cp);
        }
        return line.length();
    }

    /** Java char index to Neovim byte column within one line (clamped to the line). */
    public static int charToByteIndex(String line, int charIndex) {
        if (charIndex <= 0 || line == null || line.isEmpty()) {
            return 0;
        }
        int end = Math.min(charIndex, line.length());
        int bytes = 0;
        int i = 0;
        while (i < end) {
            int cp = line.codePointAt(i);
            bytes += utf8Length(cp);
            i += Character.charCount(cp);
        }
        return bytes;
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }
}
