// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimExceptionBase;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimVersion;

/**
 * The Neovim that was started is older than {@link NeovimVersion#MINIMUM}, so the session is not
 * started. The message says both versions, for example
 * {@code Neovim 0.12.4 is too old: Neovim for Eclipse needs 0.12.6 or newer.}; the
 * "Neovim not started" notice shows it with the executable's path, so the user sees which
 * {@code nvim} was found (often an older one from the system's package manager).
 */
public final class UnsupportedNeovimException extends NeovimExceptionBase {

    private static final long serialVersionUID = 1L;

    public UnsupportedNeovimException(String message) {
        super(message);
    }

    public UnsupportedNeovimException(String message, Throwable cause) {
        super(message, cause);
    }
}
