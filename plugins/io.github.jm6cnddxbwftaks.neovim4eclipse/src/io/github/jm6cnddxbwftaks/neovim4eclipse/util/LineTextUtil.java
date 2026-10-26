// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Conversions between Neovim's list-of-lines model and Eclipse's single-string documents.
 *
 * <h2>The final-newline model</h2>
 * Neovim has no concept of a final newline: a buffer is just N lines. A text file may or may not
 * end with a line break. The two are related exactly by:
 *
 * <pre>
 *   text = join(lines, delimiter) + (endsWithLineBreak ? delimiter : "")
 * </pre>
 *
 * <ul>
 *   <li>{@link #splitDocumentLines} drops the empty "line" after a final line break, so
 *       {@code "a\n"} is {@code ["a"]} (not {@code ["a", ""]}). Real blank lines at the end are kept:
 *       {@code "a\n\n"} is {@code ["a", ""]}.</li>
 *   <li>Whether the document has a final newline is read from the document when it is pushed to
 *       Neovim and remembered by the synchronizer ({@code sync/BufferSynchronizer}), which passes it
 *       to every edit ({@link LineRangeEdit}, the full re-sync). Edits therefore preserve whatever
 *       the file had: a file that ended with a newline still does, and one that did not still does
 *       not.</li>
 * </ul>
 *
 * <p><b>Why remembered, not read each time:</b> the text alone is ambiguous. {@code ["a", ""]}
 * without a final newline is the same text as {@code ["a"]} with one: {@code "a\n"}. That buffer is
 * what {@code o} on the last line of {@code "a"} makes. Read from the text after that edit, the
 * newline looked like a final newline, and the full sync that follows (insert mode syncs once)
 * appended a second empty line. Remembered from the push, it stays "no final newline" until Eclipse
 * pushes the document again; that push sends {@code ["a"]} with a final newline, the same file.
 *
 * <p>Round trip: {@code linesToDocumentText(splitDocumentLines(t), d, endsWithLineBreak(t))} equals
 * {@code t} whenever {@code t} uses the single delimiter {@code d}.
 */
public final class LineTextUtil {

    private LineTextUtil() {
    }

    /** Returns a mutable copy of {@code lines}, or a single empty line if there are none. */
    public static List<String> nonEmptyLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return new ArrayList<>(List.of(""));
        }
        return new ArrayList<>(lines);
    }

    /**
     * Joins lines into document text; see the class comment for the exact rule.
     *
     * @param delim            line delimiter; {@code null} or empty falls back to {@code "\n"}
     * @param finalLineBreak   whether the text ends with a delimiter
     */
    public static String linesToDocumentText(List<String> lines, String delim, boolean finalLineBreak) {
        List<String> source = nonEmptyLines(lines);
        String d = delim == null || delim.isEmpty() ? "\n" : delim;
        String joined = String.join(d, source);
        return finalLineBreak ? joined + d : joined;
    }

    /**
     * Splits document text into lines, accepting {@code \n}, {@code \r\n} and {@code \r}.
     * A final line break does not create an extra empty line; empty text yields one empty line.
     */
    public static List<String> splitDocumentLines(String text) {
        List<String> lines = new ArrayList<>();
        int n = text == null ? 0 : text.length();
        int start = 0;
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                lines.add(text.substring(start, i));
                boolean crlf = c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n';
                i += crlf ? 2 : 1;
                start = i;
            } else {
                i++;
            }
        }
        if (start < n) {
            lines.add(text.substring(start));
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    /** Whether the text ends with {@code \n} or {@code \r}. Empty text does not. */
    public static boolean endsWithLineBreak(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        char last = text.charAt(text.length() - 1);
        return last == '\n' || last == '\r';
    }

    /** Line delimiter of the host OS, used when a document does not specify one. */
    public static String platformLineDelimiter() {
        return System.lineSeparator();
    }
}
