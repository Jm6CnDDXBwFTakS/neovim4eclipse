// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * Which lines of a document may differ from Neovim's buffer, so that a push writes only those
 * (docs/design/INSERT_CTRL_KEYS.md, phase F): at {@code Esc} and at a sync point, Eclipse used to
 * read Neovim's whole buffer to find the few lines typed in, 19 ms at 100k lines.
 *
 * <h2>The idea</h2>
 * Kept as two numbers: how many lines at the start, and how many at the end, no change has touched
 * since Neovim's buffer last equaled the document. An edit leaves every line before its first line
 * and every line after its last line as they were; counted from the start and from the end, those
 * numbers stay true whatever later edits do elsewhere. So each edit only lowers them:
 *
 * <pre>
 *   20 lines, a change in line 7 (0-based)        unchanged: 7 at the start, 12 at the end
 *   then Enter typed in line 9 (10 lines after it) unchanged: 7 at the start, 10 at the end
 *   21 lines now: the push replaces Neovim's lines 7 .. end-10 with the document's lines 7 .. 10
 * </pre>
 *
 * Every change counts, Neovim's own applied to the document too: it makes those lines equal, but it
 * can shift them, and counting it only makes the range larger, never wrong.
 *
 * <h2>Final newline</h2>
 * An Eclipse document ending in a line break has one more line than Neovim's buffer, the empty one
 * after the break ({@link LineTextUtil#toNeovim}). When the last line is unchanged, so is that
 * break, and Neovim's unchanged end is one line shorter. When the last line may have changed, the
 * break may have come or gone: {@link #region} then asks for the whole document.
 *
 * <p>Pure Java, unit-tested without Eclipse. The caller reads and clears it on the UI thread, in the
 * same step as it reads the lines it pushes, so no edit falls between the two.
 */
public final class ChangedLines {

    /** Nothing changed at all: as many unchanged lines as there can be. */
    private static final int ALL = Integer.MAX_VALUE;

    private int unchangedAtStart = 0;
    private int unchangedAtEnd = 0;

    /**
     * Which lines to push.
     *
     * @param first          0-based first document line that may differ (and Neovim's, the same)
     * @param end            the document's lines {@code first .. end - 1} replace Neovim's
     * @param unchangedAtEnd how many lines at the end of Neovim's buffer stay as they are
     */
    public record Region(int first, int end, int unchangedAtEnd) {
        /** Whether no line needs to be pushed. */
        public boolean isEmpty() {
            return unchangedAtEnd == ALL;
        }
    }

    /** The region when nothing changed since the last {@link #clear}. */
    private static final Region NOTHING = new Region(0, 0, ALL);

    /** A new tracker knows nothing yet: everything may differ, until the first {@link #clear}. */
    public ChangedLines() {
    }

    /**
     * Records one edit, from the document as it is just before it.
     *
     * @param firstLine 0-based line of the edit's start offset
     * @param lastLine  0-based line of its end offset (offset + length), in the text before the edit
     * @param lineCount the document's number of lines before the edit ({@code IDocument.getNumberOfLines})
     */
    public void record(int firstLine, int lastLine, int lineCount) {
        unchangedAtStart = Math.min(unchangedAtStart, firstLine);
        unchangedAtEnd = Math.min(unchangedAtEnd, lineCount - 1 - lastLine);
    }

    /** Neovim's buffer equals the document now: nothing differs. */
    public void clear() {
        unchangedAtStart = ALL;
        unchangedAtEnd = ALL;
    }

    /** Everything may differ (a push failed, say): the next push writes the whole document. */
    public void forget() {
        unchangedAtStart = 0;
        unchangedAtEnd = 0;
    }

    /**
     * The lines to push now.
     *
     * @param lineCount    the document's number of lines now
     * @param finalNewline whether the document ends in a line break that Neovim keeps as its final
     *                     newline rather than as an empty last line ({@code LineTextUtil.NeovimLines})
     * @return the region, {@link Region#isEmpty() empty} when nothing changed, or {@code null} when
     *         the whole document must be pushed (the last line may have changed, or nothing is
     *         known yet)
     */
    public Region region(int lineCount, boolean finalNewline) {
        if (unchangedAtStart == ALL) {
            return NOTHING;
        }
        if (unchangedAtEnd == 0 || unchangedAtStart + unchangedAtEnd > lineCount) {
            return null;
        }
        int neovimEnd = unchangedAtEnd - (finalNewline ? 1 : 0);
        return new Region(unchangedAtStart, lineCount - unchangedAtEnd, neovimEnd);
    }
}
