// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Region;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.SearchMatch;

/**
 * {@link SearchRegions}: Neovim's matches as document regions, the current one, and how they move
 * with Eclipse's edits, on a plain {@link Document}. That the highlighter paints them, boxes the
 * current one and listens to the document is {@code SearchHighlighterTest} in tests/swt.
 */
class SearchRegionsTest {

    /** Line 2 starts at offset 8; {@code "é "} is three bytes but two characters. */
    private final Document document = new Document("a foo b\né foo\nnone");
    private final List<IRegion> twoFoos = List.of(new Region(2, 3), new Region(10, 3));

    @Test
    void neovimsByteColumnsBecomeDocumentOffsets() {
        assertEquals(twoFoos,
                SearchRegions.toRegions(document, List.of(new SearchMatch(1, 2, 1, 5), new SearchMatch(2, 3, 2, 6))));
    }

    @Test
    void aMatchAcrossLinesCoversTheLineBreak() {
        assertEquals(List.of(new Region(6, 3)), SearchRegions.toRegions(document, List.of(new SearchMatch(1, 6, 2, 2))),
                "b, the line break and é (two bytes)");
    }

    /** A match the document no longer has (it changed since Neovim looked) is left out, not moved. */
    @Test
    void aMatchPastTheDocumentIsLeftOut() {
        assertEquals(List.of(new Region(2, 3)),
                SearchRegions.toRegions(document, List.of(new SearchMatch(9, 0, 9, 3), new SearchMatch(1, 2, 1, 5))));
    }

    @Test
    void anEmptyMatchIsLeftOut() {
        assertEquals(List.of(), SearchRegions.toRegions(document, List.of(new SearchMatch(1, 2, 1, 2))));
    }

    @Test
    void theRegionHoldingTheOffsetIsFound() {
        assertEquals(0, SearchRegions.indexAt(twoFoos, 2));
        assertEquals(0, SearchRegions.indexAt(twoFoos, 4));
        assertEquals(-1, SearchRegions.indexAt(twoFoos, 5), "just after the end");
        assertEquals(1, SearchRegions.indexAt(twoFoos, 12));
        assertEquals(-1, SearchRegions.indexAt(List.of(), 0));
    }

    /** While a search is typed, Neovim's cursor is just after the match it jumped to: that one is current. */
    @Test
    void whileASearchIsTypedTheMatchEndingAtTheCaretIsCurrent() {
        assertEquals(-1, SearchRegions.currentAt(twoFoos, 5, false),
                "in normal mode the caret after a match is not on it");
        assertEquals(0, SearchRegions.currentAt(twoFoos, 5, true));
        assertEquals(1, SearchRegions.currentAt(twoFoos, 10, true), "a match under the caret comes first");
        assertEquals(-1, SearchRegions.currentAt(twoFoos, 0, true));
    }

    /** Text typed before a match moves it, the current one with it. */
    @Test
    void anEditBeforeTheMatchesMovesThem() {
        SearchRegions.Moved moved = SearchRegions.moved(twoFoos, 1, 0, 0, 2); // "xx" at the start
        assertEquals(List.of(new Region(4, 3), new Region(12, 3)), moved.regions());
        assertEquals(1, moved.current());
    }

    /** Text typed in a match drops it (Neovim has not matched the new text); a dropped current one is none. */
    @Test
    void anEditInAMatchDropsIt() {
        SearchRegions.Moved moved = SearchRegions.moved(twoFoos, 0, 3, 1, 1); // the o of the first foo replaced
        assertEquals(List.of(new Region(10, 3)), moved.regions());
        assertEquals(-1, moved.current());
    }

    @Test
    void anEditAfterAMatchLeavesItAndTheCurrentOneFollowsItsIndex() {
        SearchRegions.Moved moved = SearchRegions.moved(twoFoos, 1, 5, 0, 1); // right after the first foo
        assertEquals(List.of(new Region(2, 3), new Region(11, 3)), moved.regions());
        assertEquals(1, moved.current());
        assertEquals(List.of(new Region(2, 3)), SearchRegions.moved(twoFoos, -1, 9, 4, 0).regions(),
                "a deletion over the second match drops it");
    }

    /** A highlight carried along with its region: moved with its colors, or dropped, as a match is. */
    record Tagged(IRegion region, String color) {
    }

    @Test
    void followMovesWhatARegionCarriesWithIt() {
        List<Tagged> items = List.of(new Tagged(new Region(2, 3), "red"), new Tagged(new Region(10, 3), "blue"));
        assertEquals(List.of(new Tagged(new Region(4, 3), "red"), new Tagged(new Region(12, 3), "blue")),
                SearchRegions.follow(items, Tagged::region, (t, r) -> new Tagged(r, t.color()), 0, 0, 2), "xx typed at the start");
        assertEquals(List.of(new Tagged(new Region(9, 3), "blue")),
                SearchRegions.follow(items, Tagged::region, (t, r) -> new Tagged(r, t.color()), 3, 1, 0), "an edit in the first drops it");
        List<Tagged> after = SearchRegions.follow(items, Tagged::region, (t, r) -> new Tagged(r, t.color()), 20, 0, 1);
        assertSame(items.get(0), after.get(0), "an item before the change is kept as it is");
    }
}
