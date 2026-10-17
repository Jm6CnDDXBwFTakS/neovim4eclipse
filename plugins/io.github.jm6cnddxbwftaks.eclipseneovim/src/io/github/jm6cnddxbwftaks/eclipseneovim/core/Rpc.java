// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Waiting for Neovim's answers. Every neovim-java call returns a {@link CompletableFuture}; waiting
 * for it blocks the calling thread, so these helpers are for the session's input thread (and tests),
 * never the SWT UI thread.
 */
final class Rpc {

    /** Upper bound for any single RPC round trip. */
    static final long TIMEOUT_SEC = 5;

    /** A Vim error message ({@code E94: No matching buffer}) inside a longer RPC error text. */
    private static final Pattern VIM_ERROR = Pattern.compile("E\\d+: .*");

    private Rpc() {
    }

    /** Waits up to {@link #TIMEOUT_SEC} for the answer. */
    static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(TIMEOUT_SEC, TimeUnit.SECONDS);
    }

    /**
     * Neovim's message for a failed call, without the Java wrapping around it. A failed
     * {@code :b 99} arrives as an {@code ExecutionException} whose innermost cause says, among other
     * things, {@code E94: No matching buffer}; this returns just that part, or the innermost message
     * when it contains no Vim error.
     */
    static String errorMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        Matcher vimError = VIM_ERROR.matcher(message);
        return vimError.find() ? vimError.group() : message;
    }
}
