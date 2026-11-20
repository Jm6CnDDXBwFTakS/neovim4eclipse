// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

/**
 * What Neovim's status shows in Eclipse's status bar besides the mode: its {@code 'statusline'},
 * evaluated by Neovim, or {@code ""} when the status bar does not show it. Neovim's messages and its
 * pending command are drawn by Neovim itself (ui2), not here.
 */
public record StatusInfo(String statusline) {

    public static final StatusInfo EMPTY = new StatusInfo("");
}
