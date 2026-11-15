// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * What the editor shows of Neovim's command-line window ({@code q:}, {@code q/}, {@code q?},
 * {@code Ctrl-F} on the command line), as the session tells it: open or closed, its type, and its
 * grid. Example: {@code q:} gives an open view of type {@code ":"}, first without a grid, then with
 * the history drawn into it; {@code :quit} in it gives {@link #CLOSED}.
 *
 * @param open whether the window is open
 * @param type {@code ":"}, {@code "/"} or {@code "?"}: which history it edits; {@code ""} when closed
 * @param grid the window's cells as last drawn, {@code null} until Neovim has drawn them
 */
public record CommandWindowView(boolean open, String type, GridSnapshot grid) {

    /** No command-line window. */
    public static final CommandWindowView CLOSED = new CommandWindowView(false, "", null);
}
