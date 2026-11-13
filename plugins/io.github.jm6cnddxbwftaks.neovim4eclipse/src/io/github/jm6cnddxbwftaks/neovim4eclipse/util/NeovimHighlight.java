// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * One of Neovim's own highlights the editor shows besides search matches
 * (docs/design/SEARCH_HIGHLIGHTING.md, "The yank flash and window matches"): the flash after a yank,
 * or a window match ({@code :match}, {@code matchadd()}), with its group's colors. The range is in
 * Neovim's units, as a search match's. Example: the flash of {@code yiw} on {@code bar} in
 * {@code "foo bar"} with the default {@code IncSearch} is
 * {@code NeovimHighlight(new SearchMatch(1, 4, 1, 7), 0xFCE094, 0x07080D)}.
 *
 * @param range      where, as a search match
 * @param background the group's background, {@code 0xRRGGBB}
 * @param foreground the group's text color, or {@link #NO_COLOR} when the group sets none: the
 *                   editor's own syntax colors then stay
 */
public record NeovimHighlight(SearchMatch range, int background, int foreground) {

    /** A text color the group does not set. */
    public static final int NO_COLOR = -1;
}
