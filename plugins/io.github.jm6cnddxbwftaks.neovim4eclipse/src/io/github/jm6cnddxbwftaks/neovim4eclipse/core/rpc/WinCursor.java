// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc;

/**
 * A window's cursor as {@code nvim_win_get_cursor} answers it and {@code nvim_win_set_cursor}
 * takes it: the array {@code [row, col]}.
 *
 * <p>Mind the mixed bases, a classic source of off-by-one bugs: the row is 1-based (like
 * {@code line('.')}), the column 0-based and counted in UTF-8 <em>bytes</em> (unlike
 * {@code col('.')}, which is 1-based). Example: on line 2 {@code "héllo"} with the cursor on the
 * {@code l}, the answer is {@code [2, 3]}: {@code é} takes two bytes.
 *
 * <p>Only the RPC layer uses this type. The rest of the plug-in uses {@code core/CursorPos}, with the
 * same meaning, which {@code NeovimSession} converts it to: keeping the two apart means
 * {@code core/rpc} does not depend on {@code core} (which depends on it).
 *
 * @param row 1-based line
 * @param col 0-based UTF-8 byte column
 */
public record WinCursor(int row, int col) {
}
