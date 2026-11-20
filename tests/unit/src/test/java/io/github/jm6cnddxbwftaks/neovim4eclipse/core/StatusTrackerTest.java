// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewContent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewText;

/**
 * {@link StatusTracker}: the statusline for the status bar, the recording register and a page's
 * content, without a Neovim process. Neovim's messages are not here: Neovim draws them itself (ui2).
 */
class StatusTrackerTest {

    private final StatusTracker tracker = new StatusTracker();
    private final List<StatusInfo> seen = new ArrayList<>();

    StatusTrackerTest() {
        tracker.addStatusListener(seen::add);
    }

    @Test
    void theStatuslineIsPassedOnOnceEachTimeItChanges() {
        tracker.publishStatusline("main.c  12,4");
        tracker.publishStatusline("main.c  12,4");
        tracker.publishStatusline("main.c  13,1");
        assertEquals(List.of(new StatusInfo("main.c  12,4"), new StatusInfo("main.c  13,1")), seen);
        assertEquals(new StatusInfo("main.c  13,1"), tracker.current());
    }

    @Test
    void aPagesContentGoesToItsListeners() {
        List<ViewContent> pages = new ArrayList<>();
        tracker.addViewContentListener(pages::add);
        ViewText messages = ViewText.messages(List.of(new ViewText.Message("", "3 lines yanked")));
        tracker.onViewContent(messages);
        assertEquals(List.of(messages), pages);
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
