// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Waiting for Neovim's answers. Every neovim-java call returns a {@link CompletableFuture}; waiting
 * for it blocks the calling thread, so these helpers are for the session's input thread (and tests),
 * never the SWT UI thread.
 */
final class Rpc {

    /** Upper bound for any single RPC round trip. */
    static final long TIMEOUT_SEC = 5;

    private Rpc() {
    }

    /** Waits up to {@link #TIMEOUT_SEC} for the answer. */
    static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(TIMEOUT_SEC, TimeUnit.SECONDS);
    }
}
