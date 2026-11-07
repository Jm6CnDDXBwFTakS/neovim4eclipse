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
 *   <li>Whether the document has a final newline is decided at the first push to Neovim and
 *       remembered by the synchronizer ({@code sync/BufferSynchronizer}), which passes it to every
 *       edit ({@link LineRangeEdit}, the full re-sync) and to every later push
 *       ({@link #toNeovim}). Edits therefore preserve whatever the file had, as Vim's
 *       {@code 'eol'} does: a file that ended with a newline still does, and one that did not still
 *       does not.</li>
 * </ul>
 *
 * <p><b>Why remembered, not read each time:</b> the text alone is ambiguous. {@code ["a", ""]}
 * without a final newline is the same text as {@code ["a"]} with one: {@code "a\n"}. That buffer is
 * what {@code o} on the last line of {@code "a"} makes. Read from the text after that edit, the
 * newline looked like a final newline, and the full sync that follows (insert mode syncs once)
 * appended a second empty line. Remembered, it stays "no final newline", and a later push sends
 * {@code ["a", ""]} again, so the empty last line stays a line that motions reach ({@code Go},
 * Esc, {@code Go} adds a second one, as in Vim). The one change: text that no longer ends with a
 * line break (the last line break deleted in Eclipse) has no final newline from then on.
 *
 * <p>Round trip: {@code linesToDocumentText(splitDocumentLines(t), d, endsWithLineBreak(t))} equals
 * {@code t} whenever {@code t} uses the single delimiter {@code d}.
 */
public final class LineTextUtil {

    private LineTextUtil() {
    }

    /**
     * What to send to Neovim for a document's text: its lines, and whether it has a final newline
     * from now on.
     *
     * @param lines        Neovim's lines; with {@code finalNewline} false and text ending in a line
     *                     break, the last one is the empty line that break starts
     * @param finalNewline whether the text's last line break is a final newline
     */
    public record NeovimLines(List<String> lines, boolean finalNewline) {
    }

    /**
     * The lines to push to Neovim for {@code text}, given what was decided about its final newline
     * before (see the class comment).
     *
     * <p>Examples: {@code "a\n"} first pushed is {@code ["a"]} with a final newline. Pushed again
     * after {@code "a"} was known to have none, it is {@code ["a", ""]} still without one. Text
     * without a final line break never has a final newline.
     *
     * @param text  the document's text
     * @param known the final newline decided at earlier pushes, or {@code null} at the first
     */
    public static NeovimLines toNeovim(String text, Boolean known) {
        List<String> lines = splitDocumentLines(text);
        boolean endsWithBreak = endsWithLineBreak(text);
        boolean finalNewline = endsWithBreak && (known == null || known);
        if (endsWithBreak && !finalNewline) {
            lines.add(""); // the last line break starts an empty last line
        }
        return new NeovimLines(lines, finalNewline);
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

    /**
     * Whether one of Neovim's lines holds a character Eclipse's document takes as a line break:
     * {@code \r} or {@code \n} (a JFace document's default line tracker splits at {@code \r},
     * {@code \n} and {@code \r\n}, and nowhere else).
     *
     * <p>Why it matters: Neovim keeps a carriage return inside a line when it is typed with
     * {@code Ctrl-V Ctrl-M}, made by {@code :s/x/\=nr2char(13)/} or put from a register that holds
     * one. Its line {@code "\ra"} is one line in Neovim and two in Eclipse ({@code ""} and
     * {@code "a"}), so every line below it has a different number on each side: a later {@code dd}
     * in Neovim would delete the line above the right one in Eclipse. The synchronizers apply such a
     * change as Eclipse splits it and then push the lines back, so that Neovim holds the split lines
     * too and both count the same lines again (see {@code sync/BufferSynchronizer}).
     *
     * <p>A {@code \n} does not arrive in practice: Neovim stores a NUL byte as {@code \n} inside a
     * line, but its API turns it back into a NUL ({@code "a\0b"}) for an RPC client, and a NUL is an
     * ordinary character in Eclipse (pushed back, Neovim stores it as NUL again). It is checked all
     * the same, since it would split the line just like {@code \r}.
     *
     * @param lines Neovim's lines; {@code null} holds none
     */
    public static boolean holdsLineBreak(List<String> lines) {
        if (lines == null) {
            return false;
        }
        for (String line : lines) {
            if (line != null && (line.indexOf('\r') >= 0 || line.indexOf('\n') >= 0)) {
                return true;
            }
        }
        return false;
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
