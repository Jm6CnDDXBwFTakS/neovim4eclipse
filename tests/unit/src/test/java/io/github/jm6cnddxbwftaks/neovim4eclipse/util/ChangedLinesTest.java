// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
