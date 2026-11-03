// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * The Neovim preference names no Neovim the plug-in may start, so no process is started at all.
 * The "Neovim not started" notice shows the message, for example
 * {@code The Neovim program must be an absolute path (a leading ~ is fine): nvim},
 * {@code /opt/nvim is a folder: enter the Neovim program inside it, such as /opt/nvim/bin/nvim} or
 * {@code The dedicated Neovim is missing, or its files differ from Neovim 0.12.6's release (...)}.
 *
 * <p>Why refuse rather than start: given a bare name such as {@code nvim}, the operating system
 * would look it up on {@code PATH} itself, and given a folder, the plug-in would have to search it.
 * The plug-in runs only the checked dedicated Neovim or the one program named ({@link NvimExecutable}).
 */
public final class NeovimNotFoundException extends NeovimExceptionBase {

    private static final long serialVersionUID = 1L;

    public NeovimNotFoundException(String message) {
        super(message);
    }
}
