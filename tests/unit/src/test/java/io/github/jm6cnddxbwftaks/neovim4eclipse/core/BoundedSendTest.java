// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;

/**
 * {@link BoundedSend}: the caller's wait is bounded even when the write never returns, and the
 * answer or failure of a write that does return comes through. (Closing with a real stuck pipe is
 * {@code NeovimConnectionCloseTest}.)
 */
class BoundedSendTest {

    @Test
    void aWriteThatNeverReturnsLeavesTheCallerFreeAfterItsBound() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Object> answer = BoundedSend.start("test-blocked-send", () -> {
            try {
                release.await(); // a write into a pipe nobody reads
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture("late");
        });

        assertThrows(TimeoutException.class, () -> answer.get(50, TimeUnit.MILLISECONDS));

        release.countDown(); // the pipe broke: the thread finishes
        assertEquals("late", answer.get(TestWait.millis(), TimeUnit.MILLISECONDS));
    }

    @Test
    void theAnswerOfTheRequestComesThrough() throws Exception {
        CompletableFuture<Object> later = new CompletableFuture<>();
        CompletableFuture<Object> answer = BoundedSend.start("test-send", () -> later);
        later.complete(3L);
        assertEquals(3L, answer.get(TestWait.millis(), TimeUnit.MILLISECONDS));
    }

    @Test
    void aWriteThatThrowsFailsTheFuture() {
        IllegalStateException broken = new IllegalStateException("Broken pipe");
        CompletableFuture<Object> answer = BoundedSend.start("test-failing-send", () -> {
            throw broken;
        });
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> answer.get(TestWait.millis(), TimeUnit.MILLISECONDS));
        assertSame(broken, e.getCause());
    }
}
