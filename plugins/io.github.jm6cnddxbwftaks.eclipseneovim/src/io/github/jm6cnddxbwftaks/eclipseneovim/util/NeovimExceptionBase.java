// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

/**
 * The base of every exception this plug-in defines itself. Extend it for a new one.
 *
 * <p>Why a common base: a caller can catch "anything the plug-in itself raised"
 * ({@code catch (NeovimExceptionBase e)}) apart from the JDK's and the libraries' exceptions, and
 * the hierarchy shows in one place which failures the plug-in knows about. Example: an RPC call
 * that Neovim refused fails with {@code core/rpc/NeovimErrorException}; one that could not reach
 * Neovim because it exited fails with {@code core/rpc/NeovimConnectionClosedException}; both are a
 * {@code NeovimExceptionBase}.
 *
 * <p>Unchecked ({@code RuntimeException}) on purpose: these failures mostly arrive through a
 * {@code CompletableFuture}, which wraps any exception in an {@code ExecutionException} anyway, and
 * a checked exception would add {@code throws} clauses without making callers handle anything
 * more. In {@code util/} so that every package may extend it ({@code util/} depends on nothing).
 */
public abstract class NeovimExceptionBase extends RuntimeException {

    private static final long serialVersionUID = 1L;

    protected NeovimExceptionBase(String message) {
        super(message);
    }

    protected NeovimExceptionBase(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * For exceptions used as signals rather than errors, which need no stack trace: see
     * {@link RuntimeException#RuntimeException(String, Throwable, boolean, boolean)}.
     */
    protected NeovimExceptionBase(String message, Throwable cause, boolean enableSuppression,
            boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
