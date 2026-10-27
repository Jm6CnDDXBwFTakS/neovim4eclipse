// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * Some lines of a text, with their line breaks, as Vim writes them for {@code :5,10w part.txt}.
 *
 * <p>Lines are counted as Vim counts them: {@code \n}, {@code \r\n} and a lone {@code \r} each end
 * one (Eclipse keeps whichever the file has), and text after the last break is one more line. Each
 * line keeps its own break, so a file with Windows line breaks gives a part with Windows line breaks;
 * a last line without one stays without, as Eclipse would save it. Example: lines 2 to 3 of
 * {@code "a\nb\r\nc\nd"} are {@code "b\r\nc\n"}; line 4 alone is {@code "d"}.
 */
public final class TextLines {

    private TextLines() {
    }

    /**
     * Lines {@code first} to {@code last} (1-based, both included) of {@code text}. Lines past the end
     * are left out: {@code (text, 9, 12)} of a three-line text is "".
     *
     * @throws IllegalArgumentException if {@code first < 1} or {@code last < first}
     */
    public static String of(String text, int first, int last) {
        if (first < 1 || last < first) {
            throw new IllegalArgumentException("lines " + first + " to " + last);
        }
        int start = startOf(text, first);
        if (start < 0) {
            return "";
        }
        int end = startOf(text, last + 1);
        return text.substring(start, end < 0 ? text.length() : end);
    }

    /**
     * Where line {@code line} (1-based) starts, or -1 if the text has no such line. A final line break
     * starts no line: {@code "a\nb\n"} has two.
     */
    private static int startOf(String text, int line) {
        int offset = 0;
        for (int n = 1; n < line; n++) {
            offset = endOfBreak(text, offset);
            if (offset < 0 || offset == text.length()) {
                return -1;
            }
        }
        return offset;
    }

    /** The offset just after the first line break at or after {@code from}, or -1 if there is none. */
    private static int endOfBreak(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                return i + 1;
            }
            if (c == '\r') {
                return i + 1 < text.length() && text.charAt(i + 1) == '\n' ? i + 2 : i + 1;
            }
        }
        return -1;
    }
}
