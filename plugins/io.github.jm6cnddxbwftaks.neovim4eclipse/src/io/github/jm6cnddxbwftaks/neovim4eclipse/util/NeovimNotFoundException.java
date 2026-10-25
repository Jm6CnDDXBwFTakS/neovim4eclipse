// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * The Neovim preference names no Neovim the plug-in may start, so no process is started at all.
 * The "Neovim not started" notice shows the message, for example
 * {@code The Neovim folder or program must be an absolute path (a leading ~ is fine): nvim} or
 * {@code No Neovim found in the default folders}.
 *
 * <p>Why refuse rather than start: given a bare name such as {@code nvim}, the operating system
 * would look it up on {@code PATH} itself. A value in the preference is meant to name one Neovim,
 * so only a blank preference searches ({@link NvimExecutable}).
 */
public final class NeovimNotFoundException extends NeovimExceptionBase {

    private static final long serialVersionUID = 1L;

    public NeovimNotFoundException(String message) {
        super(message);
    }
}
