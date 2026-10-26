// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.List;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.BlockSelection;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimModes;

/**
 * Visual selection in Neovim coordinates: 1-based lines, 0-based <em>byte</em> columns
 * (convert with {@code Utf8Columns} before using them with Eclipse offsets).
 * {@code active == false} means "clear the Eclipse selection".
 *
 * @param block for a blockwise selection, what each line covers as Neovim's {@code getregionpos()}
 *              reports it (see {@link BlockSelection}); empty otherwise
 */
public record VisualSelection(
        boolean active,
        String mode,
        int startLine1Based,
        int startCol0Based,
        int endLine1Based,
        int endCol0Based,
        List<BlockSelection.ByteRange> block) {

    public VisualSelection {
        block = List.copyOf(block);
    }

    /** A selection without block lines (characterwise, linewise, or a block not yet read). */
    public VisualSelection(boolean active, String mode, int startLine1Based, int startCol0Based,
            int endLine1Based, int endCol0Based) {
        this(active, mode, startLine1Based, startCol0Based, endLine1Based, endCol0Based, List.of());
    }

    public static VisualSelection none() {
        return new VisualSelection(false, "", 1, 0, 1, 0);
    }

    /** Linewise visual ({@code V}). */
    public boolean isLineWise() {
        return mode != null && mode.startsWith("V");
    }

    /** Blockwise visual ({@code Ctrl-V}). */
    public boolean isBlockWise() {
        return VimModes.isBlockVisual(mode);
    }
}
