// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

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

    @Test
    void snapshotSelectionCarriesDollarModeAndTabstopForBlocks() {
        VisualSelection s = StateSnapshot.selection(
                List.of(1, 0, 1, 1, 3, 2, "\u0016", 2147483647, 4), "v");
        assertTrue(s.isBlockWise());
        assertTrue(s.toEndOfLine());
        assertEquals(4, s.tabstop());
    }

    @Test
    void snapshotSelectionDefaultsWhenBlockExtrasAreMissingOrOdd() {
        VisualSelection plain = StateSnapshot.selection(List.of(1, 0, 1, 1, 3, 2, "\u0016"), "v");
        assertFalse(plain.toEndOfLine());
        assertEquals(8, plain.tabstop());
        VisualSelection normalCurswant = StateSnapshot.selection(
                List.of(1, 0, 1, 1, 3, 2, "\u0016", 5, 0), "v");
        assertFalse(normalCurswant.toEndOfLine());
        assertEquals(1, normalCurswant.tabstop(), "tabstop is never below 1");
    }

    @Test
    void snapshotSelectionFallsBackWhenPayloadIsTooShort() {
        VisualSelection s = StateSnapshot.selection(List.of(1, 1), "v");
        assertEquals("v", s.mode());
        assertEquals(1, s.startLine1Based());
    }

    // ---- recording register ----

    @Test
    void snapshotReportsTheRecordingRegister() {
        assertEquals("a", StateSnapshot.recording(List.of(1, 0, 1, 1, 1, 1, "n", 0, 8, "a")));
        assertEquals("", StateSnapshot.recording(List.of(1, 0, 1, 1, 1, 1, "n", 0, 8, "")));
    }

    @Test
    void snapshotWithoutARecordingElementMeansNotRecording() {
        assertEquals("", StateSnapshot.recording(List.of(1, 0, 1, 1, 1, 1, "n", 0, 8)));
        assertEquals("", StateSnapshot.recording(null));
        assertEquals("", StateSnapshot.recording("junk"));
    }

    // ---- status bar parts ----

    private static List<Object> snapshot(String statusline, String showcmd, String statusmsg, String errmsg) {
        return List.of(1, 0, 1, 1, 1, 1, "n", 0, 8, "", statusline, showcmd, statusmsg, errmsg);
    }

    @Test
    void snapshotCarriesStatuslineCmdAndMessage() {
        StatusInfo info = StateSnapshot.status(snapshot("[No Name] 1,1", "2d", "3 lines yanked", ""));
        assertEquals("[No Name] 1,1", info.statusline());
        assertEquals("2d", info.cmd());
        assertEquals("3 lines yanked", info.message());
    }

    @Test
    void anErrorWinsOverAStatusMessage() {
        StatusInfo info = StateSnapshot.status(snapshot("", "", "3 lines yanked", "E486: Pattern not found: x"));
        assertEquals("E486: Pattern not found: x", info.message());
    }

    @Test
    void statusPartsAreEmptyWhenNotRequestedOrNotUnderstood() {
        assertEquals(StatusInfo.EMPTY, StateSnapshot.status(snapshot("", "", "", "")));
        assertEquals(StatusInfo.EMPTY, StateSnapshot.status(List.of(1, 0, 1, 1, 1, 1, "n", 0, 8, "")));
        assertEquals(StatusInfo.EMPTY, StateSnapshot.status(null));
        assertEquals(StatusInfo.EMPTY, StateSnapshot.status("junk"));
    }

    @Test
    void nullElementsBecomeEmptyText() {
        java.util.List<Object> withNulls = new java.util.ArrayList<>(snapshot("", "", "", ""));
        withNulls.set(10, null);
        withNulls.set(13, null);
        assertEquals(StatusInfo.EMPTY, StateSnapshot.status(withNulls));
    }

    // ---- buffer ----

    @Test
    void snapshotBufferIsTheFifteenthElement() {
        List<Object> raw = new java.util.ArrayList<>(java.util.Collections.nCopies(14, (Object) ""));
        raw.add(6);
        assertEquals(6, StateSnapshot.buffer(raw));
        assertEquals(NotificationBridge.UNKNOWN_BUFFER, StateSnapshot.buffer(raw.subList(0, 14)));
        assertEquals(NotificationBridge.UNKNOWN_BUFFER, StateSnapshot.buffer("garbage"));
    }
}
