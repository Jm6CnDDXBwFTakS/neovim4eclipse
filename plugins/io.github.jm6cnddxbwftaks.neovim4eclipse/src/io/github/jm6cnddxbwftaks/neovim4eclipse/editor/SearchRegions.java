// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Region;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.SearchMatch;

/**
 * Where Neovim's search matches are in an Eclipse document, which one is the current match, and
 * where they go when Eclipse edits the text: the arithmetic of {@code SearchHighlighter}, on the
 * text model alone (no widget, no painting), so that {@code tests/text} tests it
 * ({@code SearchRegionsTest}) without a display. {@code SearchHighlighter} keeps the widget work:
 * listeners, the lock, colors and the box.
 *
 * <p>Example: in {@code "a foo b\né foo"} Neovim reports foo at line 1, bytes 2 to 5, and at line
 * 2, bytes 3 to 6. {@link #toRegions} makes them the regions (2, 3) and (10, 3): line 2 starts at
 * offset 8, and {@code "é "} is three bytes but two characters. With the caret at 10,
 * {@link #currentAt} answers 1. Typing {@code "xx"} at the start {@link #moved moves} them to
 * (4, 3) and (12, 3).
 */
final class SearchRegions {

    private SearchRegions() {
    }

    /** The regions after a document change, and the index of the current one in them (-1 for none). */
    record Moved(List<IRegion> regions, int current) {
    }

    /**
     * The matches as document regions; a match the document does not have (it changed since Neovim
     * looked) or that comes out empty is left out.
     */
    static List<IRegion> toRegions(IDocument document, List<SearchMatch> matches) {
        List<IRegion> out = new ArrayList<>(matches.size());
        int lines = document.getNumberOfLines();
        for (SearchMatch m : matches) {
            if (m.startLine1() < 1 || m.endLine1() > lines) {
                continue; // byteColToOffset would clamp it onto another line
            }
            try {
                int start = DocumentPositions.byteColToOffset(document, m.startLine1(), m.startByte());
                int end = DocumentPositions.byteColToOffset(document, m.endLine1(), m.endByte());
                if (end > start) {
                    out.add(new Region(start, end - start));
                }
            } catch (BadLocationException e) {
                // A line the document does not have: skipped.
            }
        }
        return List.copyOf(out);
    }

    /**
     * The index of the region that holds {@code offset} (from its start up to, not including, its
     * end), or -1. Neovim's cursor sits on a match's first character after {@code n}, so the match
     * it lands on is the current one.
     */
    static int indexAt(List<IRegion> regions, int offset) {
        for (int i = 0; i < regions.size(); i++) {
            IRegion r = regions.get(i);
            if (offset >= r.getOffset() && offset < r.getOffset() + r.getLength()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The index of the current match for a caret at {@code offset}: the region that holds it, or,
     * while a search is typed ({@code searchTyped}), the one that ends there; -1 for none. While a
     * search is typed Neovim's cursor stands just after the match it jumped to (incsearch), not on it.
     */
    static int currentAt(List<IRegion> regions, int offset, boolean searchTyped) {
        int index = indexAt(regions, offset);
        if (index < 0 && searchTyped) {
            for (int i = 0; i < regions.size(); i++) {
                if (regions.get(i).getOffset() + regions.get(i).getLength() == offset) {
                    return i;
                }
            }
        }
        return index;
    }

    /**
     * The regions after the document changed by {@code removed} characters at {@code offset}
     * replaced with {@code inserted}: the ones after the change move by the difference, one it
     * touches is dropped (its text is no longer what Neovim matched), the ones before stay. The
     * current match ({@code current}, an index into {@code regions}) keeps its place in the new
     * list, or becomes -1 when it was dropped.
     */
    static Moved moved(List<IRegion> regions, int current, int offset, int removed, int inserted) {
        int newCurrent = -1;
        List<IRegion> out = new ArrayList<>(regions.size());
        for (int i = 0; i < regions.size(); i++) {
            IRegion r = movedRegion(regions.get(i), offset, removed, inserted);
            if (r == null) {
                continue;
            }
            out.add(r);
            if (i == current) {
                newCurrent = out.size() - 1;
            }
        }
        return new Moved(List.copyOf(out), newCurrent);
    }

    /**
     * The same as {@link #moved} for regions that carry something along (a highlight's colors):
     * each item's region, from {@code region}, moves or is dropped as {@link #moved} decides, and
     * {@code withRegion} makes the item again with the moved region. Example: a highlight at 10 to
     * 13 and {@code xx} typed at 2 comes back at 12 to 15, with its colors.
     */
    static <T> List<T> follow(List<T> items, Function<T, IRegion> region, BiFunction<T, IRegion, T> withRegion,
            int offset, int removed, int inserted) {
        List<T> out = new ArrayList<>(items.size());
        for (T item : items) {
            IRegion before = region.apply(item);
            IRegion after = movedRegion(before, offset, removed, inserted);
            if (after != null) {
                out.add(after == before ? item : withRegion.apply(item, after));
            }
        }
        return List.copyOf(out);
    }

    /**
     * {@code r} after the change: itself when it lies before the change, moved by the difference
     * when after it (text typed right before it included), {@code null} when the change touches it
     * (its text is no longer what Neovim matched).
     */
    private static IRegion movedRegion(IRegion r, int offset, int removed, int inserted) {
        int start = r.getOffset();
        int end = start + r.getLength();
        if (end <= offset) {
            return r;
        }
        if (start >= offset + removed) {
            return new Region(start + inserted - removed, r.getLength());
        }
        return null;
    }
}
