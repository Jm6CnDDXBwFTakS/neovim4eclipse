// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc.Mode;
import io.github.jm6cnddxbwftaks.eclipseneovim.testing.ExpectedLog;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.StatusItem;

/**
 * State reading and publishing without Neovim: a fake answers the mode and the state snapshot, and
 * the listeners record what they heard, in order, as short strings such as {@code "cursor 2:3"}.
 */
class StateSyncTest {

    /** The attached buffer in these tests; any other number is a buffer the editor does not show. */
    private static final int ATTACHED = 1;

    /** Answers set per test; {@code null} for an answer means the request fails. */
    private static final class FakeNeovim implements StateSync.Neovim {
        Mode mode = new Mode("n", false);
        Object snapshot = snapshot(1, 0, ATTACHED);
        List<String> lines = List.of("alpha");
        /** The status flags of the last snapshot request. */
        List<Boolean> snapshotFlags;

        @Override
        public Mode getMode() {
            if (mode == null) {
                throw new IllegalStateException("no answer");
            }
            return mode;
        }

        @Override
        public Object stateSnapshot(boolean statusline, boolean showcmd, boolean message) {
            snapshotFlags = List.of(statusline, showcmd, message);
            if (snapshot == null) {
                throw new IllegalStateException("timed out");
            }
            return snapshot;
        }

        @Override
        public List<String> bufferLines() {
            if (lines == null) {
                throw new IllegalStateException("no answer");
            }
            return lines;
        }
    }

    /** Records every event, and keeps the buffer changes so a test can run their after-apply action. */
    private static final class RecordingListeners implements StateSync.Listeners {
        final List<String> heard = new ArrayList<>();
        final List<BufferChange> changes = new ArrayList<>();

        @Override
        public void mode(String mode) {
            heard.add("mode " + mode);
        }

        @Override
        public void cursor(CursorPos cursor) {
            heard.add("cursor " + cursor.line1Based() + ":" + cursor.col0Based());
        }

        @Override
        public void selection(VisualSelection selection) {
            heard.add(selection.active()
                    ? "selection " + selection.mode() + " " + selection.startLine1Based() + ":"
                            + selection.startCol0Based() + "-" + selection.endLine1Based() + ":"
                            + selection.endCol0Based()
                    : "no selection");
        }

        @Override
        public void bufferChange(BufferChange change) {
            heard.add("buffer " + change.firstLine() + ".." + change.lastLine() + " " + change.newLines());
            changes.add(change);
        }
    }

    private final FakeNeovim nvim = new FakeNeovim();
    private final RecordingListeners listeners = new RecordingListeners();
    private final StatusTracker status = new StatusTracker();
    private final StateSync state = new StateSync(nvim, listeners, status, bufnr -> bufnr == ATTACHED,
            mode -> listeners.heard.add("onModeChanged " + mode)); // stands in for KeyInput#onModeChanged

    /**
     * A {@link LuaScripts#STATE_SNAPSHOT} answer (layout in {@link StateSnapshot}) with the cursor at
     * {@code line1:col0} in buffer {@code bufnr} and no visual selection.
     */
    private static List<Object> snapshot(int line1, int col0, int bufnr) {
        Object[] raw = new Object[StateSnapshot.BUFNR + 1];
        Arrays.fill(raw, "");
        raw[StateSnapshot.CURSOR_LINE] = line1;
        raw[StateSnapshot.CURSOR_COL] = col0;
        raw[StateSnapshot.BUFNR] = bufnr;
        return new ArrayList<>(Arrays.asList(raw));
    }

    /** Like {@link #snapshot}, with a visual selection from {@code getpos()} (1-based columns). */
    private static List<Object> visualSnapshot(int line1, int col0, String mode, int startLine, int startCol1,
            int endLine, int endCol1) {
        List<Object> raw = snapshot(line1, col0, ATTACHED);
        raw.set(StateSnapshot.VISUAL_BOUNDS, startLine);
        raw.set(StateSnapshot.VISUAL_BOUNDS + 1, startCol1);
        raw.set(StateSnapshot.VISUAL_BOUNDS + 2, endLine);
        raw.set(StateSnapshot.VISUAL_BOUNDS + 3, endCol1);
        raw.set(StateSnapshot.VISUAL_BOUNDS + 4, mode);
        raw.set(StateSnapshot.CURSWANT, 0);
        raw.set(StateSnapshot.TABSTOP, 8);
        return raw;
    }

    // ---- syncModeAndCursor / publishState ----

    @Test
    void inNormalModeModeCursorAndNoSelectionArePublished() {
        nvim.snapshot = snapshot(2, 3, ATTACHED);

        state.syncModeAndCursor();

        assertEquals(List.of("onModeChanged n", "mode n", "cursor 2:3", "no selection"), listeners.heard);
        assertEquals(new CursorPos(2, 3), state.lastCursor());
    }

    @Test
    void inVisualModeTheSelectionIsPublished() {
        nvim.mode = new Mode("v", false);
        nvim.snapshot = visualSnapshot(1, 4, "v", 1, 1, 1, 5);

        state.syncModeAndCursor();

        assertEquals(List.of("onModeChanged v", "mode v", "cursor 1:4", "selection v 1:0-1:4"),
                listeners.heard);
    }

    @Test
    void whileNeovimWaitsForTheRestOfACommandNothingIsPublished() {
        nvim.mode = new Mode("n", true); // after "f", waiting for the character

        state.syncModeAndCursor();

        assertEquals(List.of(), listeners.heard);
        assertNull(nvim.snapshotFlags, "no snapshot is requested while Neovim is blocked");
        assertTrue(state.isWaitingForArgument());
        assertEquals("n", state.mode());
    }

    @Test
    void cursorAndSelectionOfABufferTheEditorDoesNotShowAreDropped() {
        nvim.snapshot = snapshot(9, 9, ATTACHED + 1); // Neovim jumped to another file

        state.syncModeAndCursor();

        assertEquals(List.of("onModeChanged n", "mode n"), listeners.heard);
        assertEquals(new CursorPos(1, 0), state.lastCursor());
    }

    @Test
    void aFailedSnapshotPublishesNothingAndDoesNotThrow() {
        nvim.snapshot = null;

        state.syncModeAndCursor();

        assertEquals(List.of(), listeners.heard);
    }

    @Test
    void onlyTheStatusBarPartsShownAreRequested() {
        status.setItems(Set.of(StatusItem.CMD));

        state.publishState();

        assertEquals(List.of(false, true, false), nvim.snapshotFlags);
    }

    @Test
    void theRecordingRegisterReachesTheStatusTracker() {
        List<Object> raw = snapshot(1, 0, ATTACHED);
        raw.set(StateSnapshot.RECORDING, "q");
        nvim.snapshot = raw;

        state.publishState();

        assertEquals("q", status.recordingRegister());
    }

    // ---- readMode ----

    @Test
    void aFailedModeReadReturnsNullAndKeepsTheLastMode() {
        state.dispatchMode("i");
        nvim.mode = null;

        assertNull(state.readMode());
        assertEquals("i", state.mode());
        assertFalse(state.isBlocked());
    }

    // ---- syncBuffer and when Eclipse may type ----

    @Test
    void syncBufferPublishesTheWholeBuffer() {
        nvim.lines = List.of("one", "two");

        state.syncBuffer();

        assertEquals(List.of("buffer 0..-1 [one, two]"), listeners.heard);
    }

    @Test
    void eclipseMayTypeOnlyOnceAFullSyncWasAppliedInInsertMode() {
        state.dispatchMode("i");
        state.syncBuffer();
        assertFalse(state.isEclipseTypingReady(), "the sync is not applied yet");

        listeners.changes.get(0).afterApply().run(); // the editor applied it

        assertTrue(state.isEclipseTypingReady());
    }

    @Test
    void leavingInsertModeEndsEclipseTyping() {
        state.dispatchMode("i");
        state.syncBuffer();
        listeners.changes.get(0).afterApply().run();

        state.dispatchMode("n");
        state.dispatchMode("i"); // a new insert needs a new full sync first

        assertFalse(state.isEclipseTypingReady());
    }

    @Test
    void aFullSyncAppliedOutsideInsertModeDoesNotLetEclipseType() {
        state.syncBuffer();
        listeners.changes.get(0).afterApply().run();
        state.dispatchMode("i");

        assertFalse(state.isEclipseTypingReady());
    }

    @Test
    void aFailedBufferReadPublishesNothing() {
        try (ExpectedLog log = ExpectedLog.of(StateSync.class)) {
            nvim.lines = null;

            state.syncBuffer();

            assertEquals(List.of(), listeners.heard);
            log.assertLogged(Level.WARNING, "buffer sync failed");
        }
    }

    @Test
    void suppressedBufferEventsAreNotForwarded() {
        state.setSuppressBufferEvents(true);
        state.dispatchBufferChange(0, 1, List.of("typed"));
        state.syncBuffer();
        state.setSuppressBufferEvents(false);
        state.dispatchBufferChange(0, 1, List.of("after"));

        assertEquals(List.of("buffer 0..1 [after]"), listeners.heard);
    }

    // ---- dispatch ----

    @Test
    void aModeChangeReachesKeyInputBeforeTheListeners() {
        state.dispatchMode("i");

        assertEquals(List.of("onModeChanged i", "mode i"), listeners.heard);
        assertTrue(state.isTypingMode());
    }

    @Test
    void aCursorSetByEclipseIsRememberedWithoutTellingTheListeners() {
        state.rememberCursor(new CursorPos(5, 2));

        assertEquals(new CursorPos(5, 2), state.lastCursor());
        assertEquals(List.of(), listeners.heard);
    }
}
