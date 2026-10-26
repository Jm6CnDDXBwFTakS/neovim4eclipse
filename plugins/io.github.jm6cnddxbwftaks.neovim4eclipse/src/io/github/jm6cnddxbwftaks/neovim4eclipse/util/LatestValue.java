// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps only the newest of a stream of values until someone takes it: values that arrive faster
 * than they can be handled replace each other instead of queuing up.
 *
 * <p>Used for mouse drags: the editor reports a drag on every mouse move, possibly faster than
 * Neovim answers. Only the position the mouse has now matters, so one task at a time is queued
 * and it sends whatever position is newest when it runs.
 *
 * <p>Example: {@code offer(a)} returns {@code true} (queue a task); {@code offer(b)} returns
 * {@code false} (a task is queued already) and replaces {@code a}; the task's {@code take()} gets
 * {@code b}; the next {@code offer(c)} returns {@code true} again. Thread-safe.
 *
 * @param <T> the value type
 */
public final class LatestValue<T> {

    private final AtomicReference<T> latest = new AtomicReference<>();

    /**
     * Stores {@code value}, replacing one not taken yet.
     *
     * @return {@code true} if nothing was waiting, so the caller must arrange for a {@link #take}
     */
    public boolean offer(T value) {
        if (value == null) {
            throw new IllegalArgumentException("value is required");
        }
        return latest.getAndSet(value) == null;
    }

    /** The newest value, which is then gone; {@code null} if there is none. */
    public T take() {
        return latest.getAndSet(null);
    }
}
