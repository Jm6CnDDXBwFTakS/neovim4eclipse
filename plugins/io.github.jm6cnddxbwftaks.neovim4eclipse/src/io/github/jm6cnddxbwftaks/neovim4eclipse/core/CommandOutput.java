// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

/**
 * Output for the editor's output popup: a listing ({@code :ls}), shell output ({@code :!make}), or
 * messages of more than one line, as {@code util/MessageRoute} decides.
 *
 * @param title the command line that printed it ({@code :ls}), or "" when it did not come from a
 *              typed command (a timer, a mapping's {@code <Cmd>})
 * @param text  what was printed, without trailing blank lines; may grow while a command runs, and is
 *              then passed again, whole
 */
public record CommandOutput(String title, String text) {
}
