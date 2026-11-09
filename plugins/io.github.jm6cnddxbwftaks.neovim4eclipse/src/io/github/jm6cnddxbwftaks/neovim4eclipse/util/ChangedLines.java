// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.List;

/**
 * Which lines of a document may differ from Neovim's buffer, so that a push writes only those
 * (docs/design/INSERT_CTRL_KEYS.md, section 4): at {@code Esc} and at a sync point, reading Neovim's
 * whole buffer to find the few lines typed in would take 19 ms at 100k lines.
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
 * <h2>Telling a repair from a push</h2>
 * The same two numbers say which lines Neovim's buffer should already have: the unchanged ones.
 * A push that compares the whole buffer ({@link #COMPARE_WHOLE_UP_TO_LINES}) and finds one of those
 * lines different has repaired a drift, a difference no edit explains (a lost event, a bug).
 * {@link #known} keeps the numbers for that check, {@link Known#firstUnexplained} makes it:
 *
 * <pre>
 *   unchanged: 7 at the start, 12 at the end; Neovim's line 3 differs from the document's
 *   line 3 is not one Eclipse changed: firstUnexplained answers it (a repair)
 *   only lines 7 and 8 differ: they are the changed ones, the answer is null (an ordinary push)
 * </pre>
 *
 * <p>Pure Java, unit-tested without Eclipse. The caller reads and clears it on the UI thread, in the
 * same step as it reads the lines it pushes, so no edit falls between the two.
 */
public final class ChangedLines {

    /**
     * Documents up to this many lines are compared whole at {@code Esc} and at a sync point:
     * Neovim's whole buffer is read and diffed against the document, about 3 ms at
     * 10,000 lines. That repairs any difference between the two, wherever it is, not only in the
     * lines typed (a safety net: a lost event, or a bug elsewhere, does not stay). Longer documents
     * push only the changed lines ({@link #region}), where a whole comparison costs 20 ms and more.
     */
    public static final int COMPARE_WHOLE_UP_TO_LINES = 10_000;

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

    /**
     * Whether a document of {@code lineCount} lines is compared whole rather than pushed by region
     * ({@link #COMPARE_WHOLE_UP_TO_LINES}).
     */
    public static boolean comparesWhole(int lineCount) {
        return lineCount <= COMPARE_WHOLE_UP_TO_LINES;
    }

    /**
     * Where a whole push found a line that no recorded change explains.
     *
     * @param neovimLine 0-based line of Neovim's buffer before the push
     * @param pushedLine 0-based line of the pushed lines it should have equaled
     */
    public record Unexplained(int neovimLine, int pushedLine) {
    }

    /**
     * What a tracker knew at one moment: how many lines at the start and at the end of the
     * document no change touched since Neovim's buffer last equaled it. Taken in the same step as
     * the lines a push writes ({@link #known}), so no edit falls between the two.
     *
     * @param finalNewline as for {@link #region}: the document's last line break is Neovim's final
     *                     newline, so the document has one more line at the end than the buffer
     */
    public record Known(int unchangedAtStart, int unchangedAtEnd, boolean finalNewline) {

        /**
         * The first line that should be equal on both sides and is not: a line Neovim's buffer has
         * that no recorded change explains. Example: unchanged 2 at the start, Neovim
         * {@code ["a", "X", "c"]}, pushed {@code ["a", "b", "c!"]}: line 1 is unexplained (only lines
         * from 2 on may differ).
         *
         * @param neovim Neovim's buffer as the push found it
         * @param pushed the document's lines the push wrote ({@code LineTextUtil.toNeovim})
         * @return the first unexplained line, or {@code null} when every difference is in lines a
         *         change touched
         */
        public Unexplained firstUnexplained(List<String> neovim, List<String> pushed) {
            if (unchangedAtStart == ALL) { // nothing changed since the buffer equaled the document
                int both = Math.min(neovim.size(), pushed.size());
                for (int line = 0; line < both; line++) {
                    if (!neovim.get(line).equals(pushed.get(line))) {
                        return new Unexplained(line, line);
                    }
                }
                return neovim.size() == pushed.size() ? null : new Unexplained(both, both);
            }
            for (int line = 0; line < unchangedAtStart; line++) {
                if (line >= neovim.size() || line >= pushed.size() || !neovim.get(line).equals(pushed.get(line))) {
                    return new Unexplained(line, line);
                }
            }
            // The document's empty line after a final newline is not a buffer line (see region).
            int atEnd = unchangedAtEnd == 0 ? 0 : unchangedAtEnd - (finalNewline ? 1 : 0);
            for (int back = 1; back <= atEnd; back++) {
                int neovimLine = neovim.size() - back;
                int pushedLine = pushed.size() - back;
                if (neovimLine < 0 || pushedLine < 0 || !neovim.get(neovimLine).equals(pushed.get(pushedLine))) {
                    return new Unexplained(Math.max(neovimLine, 0), Math.max(pushedLine, 0));
                }
            }
            return null;
        }
    }

    /**
     * What this tracker knows now, for {@link Known#firstUnexplained} after a whole push. Read in the
     * same step as the pushed lines, before {@link #clear}.
     *
     * @param finalNewline as for {@link #region}
     */
    public Known known(boolean finalNewline) {
        return new Known(unchangedAtStart, unchangedAtEnd, finalNewline);
    }

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
