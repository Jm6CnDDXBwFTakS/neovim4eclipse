// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusLines;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewContent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewText;

/**
 * {@link StatusTracker}: each window's status line, the recording register and a page's
 * content, without a Neovim process. Neovim's messages are not here: Neovim draws them itself (ui2).
 */
class StatusTrackerTest {

    private final StatusTracker tracker = new StatusTracker();
    private final List<GridSnapshot> seen = new ArrayList<>();

    StatusTrackerTest() {
        tracker.addStatusLineListener(seen::add);
    }

    /** A line as {@code LuaScripts.STATUS_LINE} answers it: one cell per character, one highlight. */
    private static GridSnapshot line(int window, String text) {
        List<String> cells = text.codePoints().mapToObj(Character::toString).toList();
        List<Integer> hl = cells.stream().map(c -> 1).toList();
        return StatusLines.of(window, List.of(true, cells, hl, List.of(List.of(-1, -1, false, false, false, false, false))));
    }

    /** Example: a key that does not move the cursor leaves the line as it was, and nothing is fired. */
    @Test
    void aWindowsLineIsPassedOnOnceEachTimeItChanges() {
        tracker.publishStatusLine(line(1000, "main.c  12,4"));
        tracker.publishStatusLine(line(1000, "main.c  12,4"));
        tracker.publishStatusLine(line(1000, "main.c  13,1"));
        assertEquals(List.of(line(1000, "main.c  12,4"), line(1000, "main.c  13,1")), seen);
        assertEquals(line(1000, "main.c  13,1"), tracker.statusLine(1000));
    }

    /** Each window keeps its own last line: another window's same text is still news for it. */
    @Test
    void linesAreComparedPerWindow() {
        tracker.publishStatusLine(line(1000, "a.c"));
        tracker.publishStatusLine(line(1001, "a.c"));
        tracker.publishStatusLine(StatusLines.none(1000));
        assertEquals(List.of(line(1000, "a.c"), line(1001, "a.c"), StatusLines.none(1000)), seen);
        assertEquals(0, tracker.statusLine(1000).height());
        assertNull(tracker.statusLine(1002), "never published");
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
