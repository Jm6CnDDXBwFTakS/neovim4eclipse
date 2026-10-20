// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

/** Cursor: 1-based line, 0-based column. The column is a UTF-8 <em>byte</em> offset (Neovim convention). */
public record CursorPos(int line1Based, int col0Based) {
}
