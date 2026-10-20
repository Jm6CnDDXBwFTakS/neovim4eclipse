// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem;

/** {@link StatusTracker}: what the status bar shows, without a Neovim process. */
class StatusTrackerTest {

    private final StatusTracker tracker = new StatusTracker();
    private final List<StatusInfo> seen = new ArrayList<>();

    StatusTrackerTest() {
        tracker.setItems(EnumSet.allOf(StatusItem.class));
        tracker.addStatusListener(seen::add);
    }

    @Test
    void aMessageStaysUntilClearedEvenIfTheNextSnapshotHasNone() {
        tracker.publish(new StatusInfo("", "", "3 lines yanked"));
        tracker.publish(new StatusInfo("", "", ""));
        assertEquals("3 lines yanked", seen.get(seen.size() - 1).message());

        tracker.clearMessage();
        tracker.publish(new StatusInfo("", "", ""));
        assertEquals("", seen.get(seen.size() - 1).message());
    }

    @Test
    void clearingAMessageIsHeardAtOnce() {
        tracker.publish(new StatusInfo("", "", "E486: Pattern not found: x", true));
        tracker.clearMessage();
        StatusInfo cleared = seen.get(seen.size() - 1);
        assertEquals("", cleared.message());
        assertFalse(cleared.error());
    }

    /** {@code n} twice with no match: the second error must reach the message line again. */
    @Test
    void theSameErrorAfterTheNextKeyIsHeardAgain() {
        StatusInfo error = new StatusInfo("", "", "E486: Pattern not found: x", true);
        tracker.publish(error);
        tracker.clearMessage();
        tracker.publish(error);
        assertEquals(List.of(error, StatusInfo.EMPTY, error), seen);
    }

    @Test
    void clearingWithNoMessageFiresNothing() {
        tracker.clearMessage();
        assertTrue(seen.isEmpty());
    }

    @Test
    void theErrorFlagStaysWithItsMessage() {
        tracker.publish(new StatusInfo("", "", "E1: x", true));
        tracker.publish(new StatusInfo("", "2", ""));
        assertTrue(seen.get(seen.size() - 1).error(), "kept with the kept message");
    }

    @Test
    void pendingKeysAreAppendedToTheLastShowcmd() {
        tracker.publish(new StatusInfo("", "2", ""));
        tracker.showPendingKey("f");
        assertEquals("2f", seen.get(seen.size() - 1).cmd());
    }

    @Test
    void pendingKeysAreIgnoredWhenTheCommandPartIsHidden() {
        tracker.setItems(EnumSet.noneOf(StatusItem.class));
        tracker.showPendingKey("f");
        assertTrue(seen.isEmpty());
    }

    @Test
    void anUnchangedStatusIsNotFiredAgain() {
        tracker.publish(new StatusInfo("sl", "", ""));
        tracker.publish(new StatusInfo("sl", "", ""));
        assertEquals(1, seen.size());
    }

    @Test
    void recordingListenersHearStartAndStopOnce() {
        List<String> registers = new ArrayList<>();
        tracker.addRecordingListener(registers::add);
        tracker.updateRecording("a");
        tracker.updateRecording("a");
        tracker.updateRecording("");
        assertEquals(List.of("a", ""), registers);
        assertEquals("", tracker.recordingRegister());
    }
}
