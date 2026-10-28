// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.BlockSelection;

/** Parsing of the per-key state snapshot ({@link StateSnapshot}); no Neovim process involved. */
class StateSnapshotTest {

    // ---- visualBounds ----

    @Test
    void visualSelectionConvertsGetposColumnsToZeroBased() {
        VisualSelection s = StateSnapshot.visualBounds(List.of(2, 5, 4, 9, "v"), "v");
        assertTrue(s.active());
        assertEquals(2, s.startLine1Based());
        assertEquals(4, s.startCol0Based());
        assertEquals(4, s.endLine1Based());
        assertEquals(8, s.endCol0Based());
        assertEquals("v", s.mode());
    }

    @Test
    void visualSelectionPrefersModeReportedByLua() {
        assertEquals("V", StateSnapshot.visualBounds(List.of(1, 1, 1, 1, "V"), "v").mode());
    }

    @Test
    void visualSelectionFallsBackToCachedModeAndDefaults() {
        VisualSelection s = StateSnapshot.visualBounds("garbage", "V");
        assertTrue(s.active());
        assertEquals("V", s.mode());
        assertEquals(1, s.startLine1Based());
        assertEquals(0, s.startCol0Based());
    }

    @Test
    void visualSelectionColumnNeverGoesNegative() {
        VisualSelection s = StateSnapshot.visualBounds(List.of(1, 0, 1, 0, "v"), "v");
        assertEquals(0, s.startCol0Based());
        assertEquals(0, s.endCol0Based());
    }

    @Test
    void visualSelectionAcceptsNumericStrings() {
        VisualSelection s = StateSnapshot.visualBounds(List.of("3", "4", "5", "6"), "v");
        assertEquals(3, s.startLine1Based());
        assertEquals(3, s.startCol0Based());
    }

    @Test
    void visualSelectionKindFlags() {
        assertTrue(new VisualSelection(true, "V", 1, 0, 1, 0).isLineWise());
        assertTrue(new VisualSelection(true, "\u0016", 1, 0, 1, 0).isBlockWise());
        assertFalse(new VisualSelection(true, "v", 1, 0, 1, 0).isLineWise());
        assertFalse(VisualSelection.none().active());
    }

    // ---- state snapshot ----

    @Test
    void snapshotCursorIsTakenFromTheFirstTwoElements() {
        CursorPos pos = StateSnapshot.cursor(List.of(7, 3, 1, 1, 1, 1, "n"));
        assertEquals(7, pos.line1Based());
        assertEquals(3, pos.col0Based());
    }

    @Test
    void snapshotCursorIsNullWhenPayloadIsNotUnderstood() {
        assertEquals(null, StateSnapshot.cursor(null));
        assertEquals(null, StateSnapshot.cursor("junk"));
        assertEquals(null, StateSnapshot.cursor(List.of(1)));
    }

    @Test
    void snapshotSelectionUsesTheVisualBoundsAndTheReportedMode() {
        VisualSelection s = StateSnapshot.selection(List.of(9, 9, 2, 5, 4, 9, "V"), "v");
        assertTrue(s.active());
        assertEquals(2, s.startLine1Based());
        assertEquals(4, s.startCol0Based());
        assertEquals(4, s.endLine1Based());
        assertEquals(8, s.endCol0Based());
        assertEquals("V", s.mode());
    }

    /** Ctrl-V j l from the start of "abc" / "def": Neovim's getregionpos lines, flat. */
    @Test
    void aBlockCarriesItsLinesAsNeovimReportsThem() {
        VisualSelection s = StateSnapshot.selection(
                List.of(1, 0, 1, 1, 2, 2, "\u0016", "", "", 1, List.of(1, 1, 2, 2, 1, 2)), "v");
        assertTrue(s.isBlockWise());
        assertEquals(List.of(new BlockSelection.ByteRange(1, 1, 2), new BlockSelection.ByteRange(2, 1, 2)), s.block());
    }

    @Test
    void blockLinesAreEmptyWhenMissingAndStopAtAMalformedTriple() {
        assertEquals(List.of(), StateSnapshot.selection(List.of(1, 0, 1, 1, 3, 2, "\u0016"), "v").block());
        assertEquals(List.of(), StateSnapshot.blockLines("junk"));
        assertEquals(List.of(new BlockSelection.ByteRange(1, 1, 2)), StateSnapshot.blockLines(List.of(1, 1, 2, 2, "x", 2)));
        assertEquals(List.of(new BlockSelection.ByteRange(1, 1, 2)), StateSnapshot.blockLines(List.of(1, 1, 2, 2, 1)));
    }

    @Test
    void snapshotSelectionFallsBackWhenPayloadIsTooShort() {
        VisualSelection s = StateSnapshot.selection(List.of(1, 1), "v");
        assertEquals("v", s.mode());
        assertEquals(1, s.startLine1Based());
    }

    // ---- mode ----

    @Test
    void theModeIsNvimGetModesOrNullWhenMissing() {
        List<Object> raw = new java.util.ArrayList<>(java.util.Collections.nCopies(StateSnapshot.MODE + 1, (Object) ""));
        raw.set(StateSnapshot.MODE, "no");
        assertEquals("no", StateSnapshot.mode(raw));
        raw.set(StateSnapshot.MODE, "");
        assertNull(StateSnapshot.mode(raw), "empty");
        assertNull(StateSnapshot.mode(raw.subList(0, StateSnapshot.MODE)), "an older, shorter answer");
        assertNull(StateSnapshot.mode("not a list"));
    }

    // ---- recording register ----

    @Test
    void snapshotReportsTheRecordingRegister() {
        assertEquals("a", StateSnapshot.recording(List.of(1, 0, 1, 1, 1, 1, "n", "a")));
        assertEquals("", StateSnapshot.recording(List.of(1, 0, 1, 1, 1, 1, "n", "")));
    }

    @Test
    void snapshotWithoutARecordingElementMeansNotRecording() {
        assertEquals("", StateSnapshot.recording(List.of(1, 0, 1, 1, 1, 1, "n")));
        assertEquals("", StateSnapshot.recording(null));
        assertEquals("", StateSnapshot.recording("junk"));
    }

    // ---- statusline ----

    @Test
    void theStatuslineIsTheNinthElement() {
        assertEquals("[No Name] 1,1", StateSnapshot.statusline(List.of(1, 0, 1, 1, 1, 1, "n", "", "[No Name] 1,1", 1)));
    }

    @Test
    void theStatuslineIsEmptyWhenMissingOrNotUnderstood() {
        assertEquals("", StateSnapshot.statusline(List.of(1, 0, 1, 1, 1, 1, "n", "")));
        assertEquals("", StateSnapshot.statusline(null));
        assertEquals("", StateSnapshot.statusline("junk"));
        java.util.List<Object> withNull = new java.util.ArrayList<>(List.of(1, 0, 1, 1, 1, 1, "n", "", "", 1));
        withNull.set(StateSnapshot.STATUSLINE, null);
        assertEquals("", StateSnapshot.statusline(withNull));
    }

    // ---- buffer ----

    @Test
    void snapshotBufferIsTheTenthElement() {
        List<Object> raw = new java.util.ArrayList<>(java.util.Collections.nCopies(9, (Object) ""));
        raw.add(6);
        assertEquals(6, StateSnapshot.buffer(raw));
        assertEquals(NotificationBridge.UNKNOWN_BUFFER, StateSnapshot.buffer(raw.subList(0, 9)));
        assertEquals(NotificationBridge.UNKNOWN_BUFFER, StateSnapshot.buffer("garbage"));
    }

    @Test
    void snapshotWindowIsTheLastElement() {
        List<Object> raw = new java.util.ArrayList<>(java.util.Collections.nCopies(StateSnapshot.WINID, (Object) ""));
        raw.add(1001);
        assertEquals(1001, StateSnapshot.window(raw));
        assertEquals(EditorWindow.UNKNOWN, StateSnapshot.window(raw.subList(0, StateSnapshot.WINID)),
                "an answer without it: from any window");
        assertEquals(EditorWindow.UNKNOWN, StateSnapshot.window("garbage"));
    }

    // ---- folds ----

    private static List<Object> withFolds(Object folds) {
        List<Object> raw = new java.util.ArrayList<>(java.util.Collections.nCopies(StateSnapshot.FOLDS, (Object) ""));
        raw.add(folds);
        return raw;
    }

    @Test
    void theClosedFoldsAreTheThirteenthElement() {
        assertEquals(12, StateSnapshot.FOLDS);
        assertEquals(List.of(0, 3), StateSnapshot.closedFolds(withFolds(List.of(0L, 3L))));
        assertEquals(List.of(), StateSnapshot.closedFolds(withFolds(List.of())));
    }

    @Test
    void closedFoldsAreNullWhenMissingOrNotAList() {
        // null, not empty: an empty list would expand every fold in Eclipse.
        assertNull(StateSnapshot.closedFolds(withFolds("").subList(0, StateSnapshot.FOLDS)));
        assertNull(StateSnapshot.closedFolds(withFolds("junk")));
        assertNull(StateSnapshot.closedFolds(null));
    }

    @Test
    void entriesThatAreNotIndexesAreSkipped() {
        assertEquals(List.of(1, 4), StateSnapshot.closedFolds(withFolds(List.of(1L, "x", -2L, 4L))));
    }
}
