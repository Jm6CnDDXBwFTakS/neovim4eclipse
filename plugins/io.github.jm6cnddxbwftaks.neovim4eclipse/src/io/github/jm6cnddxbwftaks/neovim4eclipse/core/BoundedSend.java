// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Sends a message to Neovim on a short-lived thread of its own, so that a caller that must not
 * hang (the UI thread closing a session, or leaving a folder before a project delete) can wait
 * for it with a bound.
 *
 * <h2>Why the write itself needs a bound</h2>
 * {@code RpcChannel} writes a message on the calling thread, whole, under a lock, into the pipe to
 * Neovim's stdin. That write blocks when the pipe is full and Neovim does not read it: Neovim
 * hangs (a busy loop in a plug-in), and the input thread is stuck in the middle of a large
 * request (a document of a few megabytes pushed at Esc). Any other thread that writes then waits
 * for the lock, with no limit, and on the UI thread that freezes Eclipse: Restart Neovim and
 * closing the last editor would never return. A future's {@code get(timeout)} does not help: the
 * write happens before the future is even returned.
 *
 * <p>So the message is written here, on a daemon thread, and the caller waits for the returned
 * future with its timeout. If the bound passes, the caller goes on (and normally ends the
 * process); the thread stays blocked until the write fails, which it does once the process is
 * gone and the pipe broken, and then it ends. Example: {@code NeovimConnection.requestQuit} sends
 * {@code :qa!} this way and, if it is not written in time, {@code close()} destroys Neovim.
 *
 * <p>A thread per message: these are rare (a close, a project delete), so a pool would only add a
 * thread that lives for nothing.
 */
final class BoundedSend {

    private BoundedSend() {
    }

    /**
     * Runs {@code send} on a new daemon thread named {@code threadName}.
     *
     * @param send writes the message and returns the future of its answer (an already completed one
     *             for a notification, which has none)
     * @return completes with the answer, or fails with what {@code send} threw or the answer's failure.
     *         Wait for it with a timeout.
     */
    static <T> CompletableFuture<T> start(String threadName, Supplier<CompletableFuture<T>> send) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                send.get().whenComplete((value, failure) -> {
                    if (failure == null) {
                        result.complete(value);
                    } else {
                        result.completeExceptionally(failure);
                    }
                });
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        }, threadName);
        thread.setDaemon(true); // never keeps the IDE from exiting, even while blocked in a write
        thread.start();
        return result;
    }
}
