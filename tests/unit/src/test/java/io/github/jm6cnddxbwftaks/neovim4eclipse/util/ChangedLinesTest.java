// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Which lines a push writes ({@link ChangedLines}). */
class ChangedLinesTest {

    private final ChangedLines changed = new ChangedLines();

    @Test
    void documentsUpToTheLimitAreComparedWhole() {
        assertTrue(ChangedLines.comparesWhole(ChangedLines.COMPARE_WHOLE_UP_TO_LINES));
        assertFalse(ChangedLines.comparesWhole(ChangedLines.COMPARE_WHOLE_UP_TO_LINES + 1));
    }

    @Test
    void beforeTheFirstClearEverythingIsPushed() {
        assertNull(changed.region(20, false));
    }

    @Test
    void nothingChangedPushesNothing() {
        changed.clear();
        assertTrue(changed.region(20, false).isEmpty());
    }

    /** The class comment's example: line 7 changed, then Enter in line 9. */
    @Test
    void editsLowerTheUnchangedLinesAtBothEnds() {
        changed.clear();
        changed.record(7, 7, 20);
        assertEquals(new ChangedLines.Region(7, 8, 12), changed.region(20, false));
        changed.record(9, 9, 20); // Enter: 21 lines after it
        assertEquals(new ChangedLines.Region(7, 11, 10), changed.region(21, false));
    }

    /** An edit after the range does not move its start; one before it does not move its end count. */
    @Test
    void editsElsewhereOnlyWidenTheRange() {
        changed.clear();
        changed.record(10, 10, 20);
        changed.record(2, 3, 20); // two lines joined: 19 lines now
        assertEquals(new ChangedLines.Region(2, 10, 9), changed.region(19, false));
    }

    /** A document ending in a line break has the empty line after it, which Neovim's buffer lacks. */
    @Test
    void theFinalNewlinesEmptyLineIsNotNeovims() {
        changed.clear();
        changed.record(1, 1, 4); // "a\nb\nc\n": lines a, b, c and the empty one
        assertEquals(new ChangedLines.Region(1, 2, 1), changed.region(4, true), "Neovim keeps c");
        assertEquals(new ChangedLines.Region(1, 2, 2), changed.region(4, false), "the empty line is Neovim's too");
    }

    /** The last line changed: a final line break may have come or gone, so the whole document goes. */
    @Test
    void aChangeOfTheLastLinePushesEverything() {
        changed.clear();
        changed.record(3, 3, 4);
        assertNull(changed.region(4, true));
    }

    @Test
    void forgetAndClear() {
        changed.clear();
        changed.record(1, 1, 5);
        changed.forget();
        assertNull(changed.region(5, false), "a failed push: everything next time");
        changed.clear();
        assertTrue(changed.region(5, false).isEmpty());
    }

    /** A whole-document replace (document.set) covers every line. */
    @Test
    void replacingTheWholeDocumentPushesEverything() {
        changed.clear();
        changed.record(0, 4, 5);
        assertNull(changed.region(3, false));
    }

    // ---- telling a repair from an ordinary push (Known.firstUnexplained) ----

    /** Neovim's buffer, as a whole push finds it, and the lines it pushes. */
    private static final List<String> PUSHED = List.of("a", "b", "c", "d");

    /** The class comment's example: a change in the middle explains only its own lines. */
    @Test
    void aDifferenceInTheChangedLinesIsAnOrdinaryPush() {
        changed.clear();
        changed.record(1, 1, 4); // line 1 typed in
        assertNull(changed.known(false).firstUnexplained(List.of("a", "B", "c", "d"), PUSHED));
    }

    @Test
    void aDifferenceAboveTheChangedLinesIsARepair() {
        changed.clear();
        changed.record(2, 2, 4);
        assertEquals(new ChangedLines.Unexplained(0, 0),
                changed.known(false).firstUnexplained(List.of("X", "b", "C", "d"), PUSHED));
    }

    @Test
    void aDifferenceBelowTheChangedLinesIsARepair() {
        changed.clear();
        changed.record(1, 1, 4);
        assertEquals(new ChangedLines.Unexplained(3, 3),
                changed.known(false).firstUnexplained(List.of("a", "B", "c", "X"), PUSHED));
    }

    /** Enter typed in line 1: Neovim has one line fewer, and the lines below are only moved. */
    @Test
    void linesAddedByAChangeAreMatchedFromTheEnd() {
        changed.clear();
        changed.record(1, 1, 3); // "a", "bc", "d" before, "b" Enter "c" after
        assertNull(changed.known(false).firstUnexplained(List.of("a", "bc", "d"), PUSHED));
    }

    /**
     * A typed line equal to the one below it ({@code "}"} above {@code "}"}) does not fool the check,
     * as the smallest diff would: it compares the unchanged lines, not the diff's ends.
     */
    @Test
    void aTypedLineEqualToItsNeighborIsAnOrdinaryPush() {
        changed.clear();
        changed.record(1, 1, 3); // "}" Enter typed at the start of line 1
        assertNull(changed.known(false).firstUnexplained(List.of("a", "}", "z"), List.of("a", "}", "}", "z")));
    }

    @Test
    void nothingChangedYetADifferenceIsARepair() {
        changed.clear();
        assertEquals(new ChangedLines.Unexplained(2, 2),
                changed.known(false).firstUnexplained(List.of("a", "b", "X", "d"), PUSHED));
        assertEquals(new ChangedLines.Unexplained(4, 4),
                changed.known(false).firstUnexplained(List.of("a", "b", "c", "d", "e"), PUSHED), "a line too many");
        assertNull(changed.known(false).firstUnexplained(PUSHED, PUSHED));
    }

    /** A tracker that knows nothing (before the first push, after a failed one) claims nothing. */
    @Test
    void aTrackerThatKnowsNothingExplainsEverything() {
        assertNull(changed.known(false).firstUnexplained(List.of("X", "Y"), PUSHED));
        changed.clear();
        changed.forget();
        assertNull(changed.known(false).firstUnexplained(List.of("X", "Y"), PUSHED));
    }

    /**
     * The document's empty line after a final newline is no buffer line: one unchanged line at the
     * end of the document ("" after "d\n") is none of Neovim's.
     */
    @Test
    void aFinalNewlineIsNotOneOfNeovimsLines() {
        changed.clear();
        changed.record(3, 3, 5); // "a\nb\nc\nd\n": line 3 ("d") typed in, line 4 is the empty one
        assertNull(changed.known(true).firstUnexplained(List.of("a", "b", "c", "D"), PUSHED));
        assertEquals(new ChangedLines.Unexplained(3, 3),
                changed.known(false).firstUnexplained(List.of("a", "b", "c", "D"), PUSHED),
                "without a final newline, the last document line is Neovim's");
    }

    /** A change of the last line claims nothing at the end: only the lines above are checked. */
    @Test
    void aChangeOfTheLastLineChecksOnlyTheLinesAbove() {
        changed.clear();
        changed.record(2, 3, 4);
        assertNull(changed.known(false).firstUnexplained(List.of("a", "b", "C"), PUSHED));
        assertEquals(new ChangedLines.Unexplained(1, 1),
                changed.known(false).firstUnexplained(List.of("a", "X", "c"), PUSHED));
    }
}
