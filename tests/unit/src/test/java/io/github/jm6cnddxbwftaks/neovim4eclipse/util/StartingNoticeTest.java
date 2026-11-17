// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The order {@link StartingNotice} keeps around the synchronous start: message, repaint, then the
 * start under the busy cursor, then the message cleared, also after a failure. Without SWT: each
 * step records itself.
 */
class StartingNoticeTest {

    private final List<String> steps = new ArrayList<>();

    private <T> T around(boolean starting, StartingNotice.Start<T> start) throws Exception {
        return StartingNotice.around(starting, message -> steps.add("status " + message), () -> steps.add("repaint"),
                work -> {
                    steps.add("busy on");
                    work.run();
                    steps.add("busy off");
                }, start);
    }

    @Test
    void theMessageIsPaintedBeforeTheStartAndClearedAfter() throws Exception {
        String session = around(true, () -> {
            steps.add("start");
            return "session";
        });
        assertEquals("session", session);
        assertEquals(List.of("status " + StartingNotice.MESSAGE, "repaint", "busy on", "start", "busy off", "status null"),
                steps);
    }

    @Test
    void aFailedStartClearsTheMessageThenThrows() {
        IllegalStateException failure = new IllegalStateException("no nvim");
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> around(true, () -> {
            steps.add("start");
            throw failure;
        }));
        assertSame(failure, thrown);
        assertEquals(List.of("status " + StartingNotice.MESSAGE, "repaint", "busy on", "start", "busy off", "status null"),
                steps);
    }

    @Test
    void aRunningNeovimShowsNothing() throws Exception {
        assertEquals("session", around(false, () -> {
            steps.add("start");
            return "session";
        }));
        assertEquals(List.of("start"), steps);
    }
}
