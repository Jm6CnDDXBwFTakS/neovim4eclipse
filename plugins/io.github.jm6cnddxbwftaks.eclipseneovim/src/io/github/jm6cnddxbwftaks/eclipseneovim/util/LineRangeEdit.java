// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import java.util.List;

/**
 * Translates a Neovim line-range change ({@code nvim_buf_lines_event}) into a single
 * text replacement on an Eclipse-style document.
 *
 * <p>This is the trickiest piece of the Neovim to Eclipse sync, so it is kept free of Eclipse
 * types and covered by randomized tests. The document is accessed through {@link LineSource}.
 * The edit is as small as possible: unchanged text at either end of the lines is left out.
 *
 * <h2>Conventions</h2>
 * <ul>
 *   <li>A Neovim event replaces lines {@code [first, last)} (0-based, {@code last} exclusive)
 *       with {@code newLines}. Pure insertion has {@code first == last}; pure deletion has
 *       empty {@code newLines}.</li>
 *   <li>Eclipse counts an extra empty "phantom" line when a document ends with a line break.
 *       Neovim's line count is therefore {@code lineCount()} minus one in that case.</li>
 *   <li>The result follows the exact rule in {@link LineTextUtil}: the document keeps ending (or
 *       not ending) with a line break exactly as it did before the edit.</li>
 * </ul>
 */
public final class LineRangeEdit {

    /** Minimal read-only view of a line-oriented document. */
    public interface LineSource {
        /** Number of lines, counting the empty line after a trailing delimiter. */
        int lineCount();

        /** Offset of the first character of {@code line} ({@code 0 <= line < lineCount()}). */
        int lineOffset(int line);

        /** Length of the line excluding its delimiter. */
        int lineContentLength(int line);

        /** Total document length. */
        int length();

        /** Document substring. */
        String text(int offset, int length);
    }

    /** A replacement of {@code length} characters at {@code offset} with {@code text}. */
    public record Edit(int offset, int length, String text) {
    }

    private LineRangeEdit() {
    }

    /**
     * Computes the minimal replacement, or {@code null} if the document already matches.
     *
     * @param doc      current document
     * @param first    first changed line (0-based, clamped into the buffer)
     * @param last     line after the last changed one; {@code < 0} or past the end means "through the last line"
     * @param newLines replacement lines; {@code null} is treated as empty
     * @param delim    line delimiter to insert
     */
    public static Edit compute(LineSource doc, int first, int last, List<String> newLines, String delim) {
        List<String> lines = newLines == null ? List.of() : newLines;
        boolean finalLineBreak = endsWithLineBreak(doc);
        // Neovim's line count: Eclipse's phantom empty last line is not a Neovim line.
        int count = finalLineBreak ? doc.lineCount() - 1 : doc.lineCount();
        int firstLine = Math.max(0, Math.min(first, count));
        int lastLine = last < 0 ? count : Math.max(firstLine, Math.min(last, count));

        // Fast path: one existing line replaced by one line. Touch only its content so the
        // delimiter and later lines are left alone.
        if (lines.size() == 1 && firstLine < count && lastLine == firstLine + 1) {
            return diff(doc, doc.lineOffset(firstLine), doc.lineContentLength(firstLine), lines.get(0));
        }

        int start = firstLine < count ? doc.lineOffset(firstLine) : doc.length();
        int end = lastLine < count ? doc.lineOffset(lastLine) : doc.length();

        StringBuilder replacement = new StringBuilder();
        // Appending after a last line that has no line break: start a new line first.
        if (!lines.isEmpty() && firstLine >= count && !finalLineBreak) {
            replacement.append(delim);
        }
        replacement.append(String.join(delim, lines));
        // The replaced span [start, end) includes the line break of its last line whenever a later
        // line exists (or the document ends with a line break), so the replacement must end with one.
        if (!lines.isEmpty() && (lastLine < count || finalLineBreak)) {
            replacement.append(delim);
        }
        // Deleting through an unterminated last line: also remove the line break that preceded it,
        // otherwise the document would gain a final line break it did not have.
        if (lines.isEmpty() && firstLine < lastLine && lastLine >= count && !finalLineBreak && firstLine > 0) {
            start = doc.lineOffset(firstLine - 1) + doc.lineContentLength(firstLine - 1);
        }
        return diff(doc, start, end - start, replacement.toString());
    }

    /**
     * The replacement of {@code [offset, offset + length)}, trimmed to the characters that really
     * change. Neovim reports whole lines, but Eclipse features that track text positions need the
     * small edit: in a linked rename (Alt+Shift+R) an edit that reaches outside the name ends the
     * rename, so {@code ciw} on the name must arrive as "replace the name", not "replace the line".
     */
    private static Edit diff(LineSource doc, int offset, int length, String replacement) {
        Edit inSpan = TextDiff.minimalEdit(doc.text(offset, length), replacement);
        return inSpan == null ? null : new Edit(offset + inSpan.offset(), inSpan.length(), inSpan.text());
    }

    private static boolean endsWithLineBreak(LineSource doc) {
        int len = doc.length();
        if (len == 0) {
            return false; // an empty document is one empty line without a final line break
        }
        char c = doc.text(len - 1, 1).charAt(0);
        return c == '\n' || c == '\r';
    }
}
