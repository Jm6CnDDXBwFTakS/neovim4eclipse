// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@link InputThread}: order, failure isolation and shutdown. */
class InputThreadTest {

    private final InputThread thread = new InputThread();

    @AfterEach
    void shutdown() {
        thread.shutdown();
    }

    @Test
    void tasksRunInSubmissionOrder() throws Exception {
        List<Integer> order = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        for (int i = 0; i < 20; i++) {
            int n = i;
            thread.submit("task " + n, Level.FINE, () -> order.add(n));
        }
        thread.submit("done", Level.FINE, done::countDown);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(20, order.size());
        for (int i = 0; i < 20; i++) {
            assertEquals(i, order.get(i));
        }
    }

    @Test
    void aFailingTaskDoesNotStopTheNextOne() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        thread.submit("fails", Level.FINE, () -> {
            throw new IllegalStateException("expected by the test");
        });
        thread.submit("runs", Level.FINE, ran::countDown);
        assertTrue(ran.await(5, TimeUnit.SECONDS));
    }

    @Test
    void workSubmittedAfterShutdownIsDroppedQuietly() {
        thread.shutdown();
        thread.submit("dropped", Level.FINE, () -> {
            throw new AssertionError("must not run");
        });
    }
}
