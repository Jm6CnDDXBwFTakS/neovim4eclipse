// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.util.List;

import org.eclipse.jface.text.IDocument;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.BlockSelection.LineRange;

/**
 * Where a blockwise visual selection ({@code Ctrl-V}) is shown: one range of characters per line.
 *
 * <p>The editor implements it with {@link BlockOverlay}, which paints rectangles over a
 * {@code StyledText} and so needs SWT and a display. {@link VisualSelectionView} only decides
 * <em>which</em> ranges to show; behind this interface its tests run without SWT, with a fake that
 * records the ranges (see {@code VisualSelectionViewTest} in {@code tests/text}, and
 * {@code docs/LEARNING.md}, "Ports and fakes"). UI thread only.
 */
interface BlockHighlight {

    /**
     * Shows {@code lineRanges} of {@code doc}, replacing what was shown; an empty list removes it.
     * Example: a block from line 1 col 1 to line 3 col 2 gives three ranges, one per line.
     */
    void show(IDocument doc, List<LineRange> lineRanges);

    /** Removes the highlight (same as {@code show(doc, List.of())}). */
    void clear();

    /** Removes the highlight for good and releases what it holds (listeners, colors). */
    void dispose();
}
