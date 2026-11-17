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
 * {@link BusyStart}: the start runs inside the busy cursor, on the same thread, and its answer or
 * failure comes out after it. Without SWT: each step records itself.
 */
class BusyStartTest {

    private final List<String> steps = new ArrayList<>();

    private <T> T run(boolean starting, BusyStart.Start<T> start) throws Exception {
        return BusyStart.run(starting, work -> {
            steps.add("busy on " + Thread.currentThread().getName());
            work.run();
            steps.add("busy off");
        }, start);
    }

    @Test
    void theStartRunsUnderTheBusyCursorOnTheSameThread() throws Exception {
        String thread = Thread.currentThread().getName();
        assertEquals("session", run(true, () -> {
            steps.add("start " + Thread.currentThread().getName());
            return "session";
        }));
        assertEquals(List.of("busy on " + thread, "start " + thread, "busy off"), steps);
    }

    @Test
    void aFailedStartThrowsOnceTheBusyCursorIsGone() {
        IllegalStateException failure = new IllegalStateException("no nvim");
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> run(true, () -> {
            steps.add("start");
            throw failure;
        }));
        assertSame(failure, thrown);
        assertEquals(List.of("busy on " + Thread.currentThread().getName(), "start", "busy off"), steps);
    }

    @Test
    void aRunningNeovimIsNotBusy() throws Exception {
        assertEquals("session", run(false, () -> {
            steps.add("start");
            return "session";
        }));
        assertEquals(List.of("start"), steps);
    }
}
