// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.List;

/**
 * A change to the Neovim buffer: lines {@code [firstLine, lastLine)} (0-based, exclusive end)
 * are replaced by {@code newLines}. {@code lastLine == -1} means "the whole buffer is
 * {@code newLines}" (used for full re-syncs).
 *
 * @param afterApply run by the listener that applies the change to the Eclipse document, on the
 *                   UI thread, right after applying it; {@code null} for none
 */
public record BufferChange(int firstLine, int lastLine, List<String> newLines, Runnable afterApply) {

    public BufferChange(int firstLine, int lastLine, List<String> newLines) {
        this(firstLine, lastLine, newLines, null);
    }

    /**
     * A change of no line, only to run {@code afterApply} once the editor has applied every change
     * sent before it (they are applied in order). Example: at a sync point in a long document,
     * Eclipse may type again once Ctrl-W's own line events have reached the document, without
     * reading Neovim's whole buffer for it.
     */
    public static BufferChange marker(Runnable afterApply) {
        return new BufferChange(0, 0, List.of(), afterApply);
    }

    /** Whether this replaces no line with none: nothing to apply ({@link #marker}). */
    public boolean changesNothing() {
        return lastLine == firstLine && newLines.isEmpty();
    }
}
