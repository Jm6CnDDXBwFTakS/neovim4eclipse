// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.VimModes;

/**
 * Visual selection in Neovim coordinates: 1-based lines, 0-based <em>byte</em> columns
 * (convert with {@code Utf8Columns} before using them with Eclipse offsets).
 * {@code active == false} means "clear the Eclipse selection". {@code toEndOfLine} and
 * {@code tabstop} matter for blockwise selections only (see {@code BlockSelection}).
 */
public record VisualSelection(
        boolean active,
        String mode,
        int startLine1Based,
        int startCol0Based,
        int endLine1Based,
        int endCol0Based,
        boolean toEndOfLine,
        int tabstop) {

    /** Selection without block extras ({@code $} not active, tab width 8). */
    public VisualSelection(boolean active, String mode, int startLine1Based, int startCol0Based,
            int endLine1Based, int endCol0Based) {
        this(active, mode, startLine1Based, startCol0Based, endLine1Based, endCol0Based, false, 8);
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
