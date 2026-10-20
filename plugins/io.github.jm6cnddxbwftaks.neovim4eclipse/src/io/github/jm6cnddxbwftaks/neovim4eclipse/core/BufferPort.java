// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.List;
import java.util.function.Consumer;

/**
 * What keeping one Eclipse document in step with Neovim's buffer needs from the session: write the
 * buffer, queue work after the keys already sent, and hear about Neovim's own changes.
 *
 * <p>An interface (rather than the whole {@link NeovimSession}) so that the synchronizer
 * ({@code sync/BufferSynchronizer}) can be tested with a small fake that records the pushed lines
 * and runs queued work when the test says so. {@link NeovimSession} implements it.
 */
public interface BufferPort {

    /**
     * Makes the attached buffer's text equal to {@code lines}. Blocking RPC: call on the session's
     * input thread, that is, from work given to {@link #runAsync}.
     */
    void setBufferLines(List<String> lines) throws Exception;

    /**
     * Runs {@code work} on the input thread, after every key and command queued before it.
     *
     * @param what short description for the log, should {@code work} fail
     */
    void runAsync(String what, Runnable work);

    /** Notified (on a session thread) of each change Neovim makes to the attached buffer. */
    void addBufferChangeListener(Consumer<BufferChange> listener);

    void removeBufferChangeListener(Consumer<BufferChange> listener);
}
