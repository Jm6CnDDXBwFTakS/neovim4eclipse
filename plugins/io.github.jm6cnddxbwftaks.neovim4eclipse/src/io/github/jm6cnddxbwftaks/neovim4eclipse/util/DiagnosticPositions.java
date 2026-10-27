// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Diagnostic.Severity;

/**
 * Turns Eclipse problems, placed by document offset, into Neovim {@link Diagnostic}s, placed by line
 * and UTF-8 byte column.
 *
 * <h2>The two coordinate systems</h2>
 * Eclipse's annotation model gives each problem an offset and a length in the document, counted in
 * UTF-16 chars over the whole text, line breaks included. Neovim wants a 0-based line and a byte
 * column within it ({@link Utf8Columns}). Example, the document {@code "int x;\nString é = 1;\n"} with
 * an error on {@code 1} (offset 18, length 1): line 1, char column 11, byte column 12 (the
 * {@code é} before it is two bytes), so {@code lnum 1, col 12, endLnum 1, endCol 13}.
 *
 * <p>Annotation positions are document offsets, so folding does not matter here ({@code WidgetOffsets}
 * is only for {@code StyledText} offsets).
 *
 * <h2>Out of range</h2>
 * A position past the end of the text (a problem Eclipse has not yet moved after an edit) is clamped
 * to the end of Neovim's last line, rather than dropped: Neovim's {@code ]d} still stops near it, and
 * the next update from Eclipse puts it right. Neovim has no line after a final line break
 * ({@link Lines#lineCount}), so an offset there also lands at the end of the last line.
 *
 * <p>No Eclipse types: the caller adapts its document to {@link Lines}
 * ({@code sync/DiagnosticsSync} for an {@code IDocument}), and {@code tests/unit} uses plain strings.
 */
public final class DiagnosticPositions {

    private DiagnosticPositions() {
    }

    /** The lines of a document, as the conversion needs them. Lines are 0-based. */
    public interface Lines {

        /** The length of the whole text, in chars. */
        int length();

        /**
         * How many lines Neovim's buffer has for this text: one less than Eclipse counts when the text
         * ends with a line break (Eclipse counts an empty line after it, Neovim does not).
         */
        int lineCount();

        /** The line that holds {@code offset} (0 to {@link #length}, inclusive). */
        int lineOf(int offset);

        /** The offset where {@code line} starts. */
        int lineStart(int line);

        /** The text of {@code line}, without its line break. */
        String lineText(int line);
    }

    /**
     * One problem as Eclipse places it.
     *
     * @param offset   where it starts in the document, in chars
     * @param length   how many chars it covers; 0 for a problem at a point (a missing {@code ;})
     * @param severity how bad it is
     * @param message  Eclipse's text for it; {@code null} counts as empty
     */
    public record Problem(int offset, int length, Severity severity, String message) {
    }

    /** Neovim's order: by start, then end, then severity (errors first), so that a list compares equal when nothing changed. */
    private static final Comparator<Diagnostic> ORDER = Comparator.comparingInt(Diagnostic::lnum)
            .thenComparingInt(Diagnostic::col)
            .thenComparingInt(Diagnostic::endLnum)
            .thenComparingInt(Diagnostic::endCol)
            .thenComparing(Diagnostic::severity)
            .thenComparing(Diagnostic::message);

    /** The diagnostics for {@code problems} in a text with these {@code lines}, sorted by position. */
    public static List<Diagnostic> toDiagnostics(List<Problem> problems, Lines lines) {
        List<Diagnostic> diagnostics = new ArrayList<>(problems.size());
        for (Problem problem : problems) {
            diagnostics.add(toDiagnostic(problem, lines));
        }
        diagnostics.sort(ORDER);
        return diagnostics;
    }

    /** One problem as a diagnostic. A negative length counts as 0. */
    public static Diagnostic toDiagnostic(Problem problem, Lines lines) {
        int start = clamp(problem.offset(), lines.length());
        // The end from the offset as given, so that clamping the start does not lengthen the range.
        int end = Math.max(start, clamp(problem.offset() + Math.max(0, problem.length()), lines.length()));
        int[] from = position(start, lines);
        int[] to = position(end, lines);
        return new Diagnostic(from[0], from[1], to[0], to[1], problem.severity(), problem.message());
    }

    /** {@code {line, byte column}} of a document offset, within Neovim's lines. */
    private static int[] position(int offset, Lines lines) {
        int lastLine = Math.max(0, lines.lineCount() - 1);
        int line = lines.lineOf(offset);
        if (line > lastLine) {
            String last = lines.lineText(lastLine);
            return new int[] {lastLine, Utf8Columns.charToByteIndex(last, last.length())};
        }
        String text = lines.lineText(line);
        // An offset inside a "\r\n" (or just after the text) clamps to the end of the line's text.
        return new int[] {line, Utf8Columns.charToByteIndex(text, offset - lines.lineStart(line))};
    }

    private static int clamp(int offset, int length) {
        return Math.max(0, Math.min(offset, length));
    }
}
