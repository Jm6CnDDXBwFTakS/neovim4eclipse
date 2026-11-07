// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * One match of Neovim's search (docs/design/SEARCH_HIGHLIGHTING.md), in Neovim's units: lines
 * 1-based, columns UTF-8 bytes from 0, the end exclusive. Example: {@code foo} at the start of line
 * 3 is {@code SearchMatch(3, 0, 3, 3)}; a match of {@code foo\nbar} from column 4 of line 1 is
 * {@code SearchMatch(1, 4, 2, 3)}. The editor turns it into document offsets.
 */
public record SearchMatch(int startLine1, int startByte, int endLine1, int endByte) {
}
