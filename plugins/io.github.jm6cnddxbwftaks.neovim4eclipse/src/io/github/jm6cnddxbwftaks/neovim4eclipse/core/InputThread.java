// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The single thread on which a {@link NeovimSession} makes every blocking RPC call.
 *
 * <p>Why one thread: keys must reach Neovim in the order they were typed ({@code ciw} is three
 * keys), and the SWT UI thread must never wait for Neovim, or a stalled call would freeze the IDE.
 * Queuing all work here solves both. Example:
 *
 * <pre>{@code
 * inputThread.submit("input 'x'", Level.WARNING, () -> keyInput.input("x"));
 * }</pre>
 *
 * <p>A failing task is logged at the level given with it and does not stop the thread. Work
 * submitted after {@link #shutdown()} is dropped (logged at FINE): the session is closing anyway.
 */
final class InputThread {

    private static final Logger LOG = Logger.getLogger(InputThread.class.getName());

    /** Work that may throw; any exception is logged by {@link InputThread#submit}. */
    @FunctionalInterface
    interface Task {
        void run() throws Exception;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "neovim4eclipse-input");
        t.setDaemon(true); // never keeps the JVM (Eclipse) from exiting
        return t;
    });

    /**
     * Queues {@code work} behind everything submitted before.
     *
     * @param what         short description for the log
     * @param failureLevel level at which a failure of {@code work} is logged
     */
    void submit(String what, Level failureLevel, Task work) {
        try {
            executor.execute(() -> {
                try {
                    work.run();
                } catch (Exception e) {
                    LOG.log(failureLevel, what + " failed", e);
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.log(Level.FINE, "Input thread shut down; dropped: " + what);
        }
    }

    /** Interrupts the running task and drops the queued ones. */
    void shutdown() {
        executor.shutdownNow();
    }
}
