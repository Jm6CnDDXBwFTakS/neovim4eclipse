// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.NeovimExceptionBase;

/**
 * A call could not get an answer because the connection to Neovim ended: Neovim exited (on
 * purpose with {@code :qa!}, or by crashing), the pipe broke, or the channel was closed.
 *
 * <p>Every request still waiting at that moment fails with this at once, instead of waiting for
 * its full timeout; so does a request or notification sent afterwards. Compare
 * {@link NeovimErrorException}: there Neovim answered, and the answer was "no".
 */
public final class NeovimConnectionClosedException extends NeovimExceptionBase {

    private static final long serialVersionUID = 1L;

    public NeovimConnectionClosedException(String message) {
        super(message);
    }

    public NeovimConnectionClosedException(String message, Throwable cause) {
        super(message, cause);
    }
}
