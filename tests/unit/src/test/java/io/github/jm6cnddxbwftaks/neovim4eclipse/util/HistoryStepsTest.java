// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link HistorySteps} against a model of Eclipse's navigation history ({@link History}): a list
 * with a current entry, where a new entry deletes the entries ahead of it, as Eclipse's does.
 */
class HistoryStepsTest {

    /** A history entry's location; compared by identity, as Eclipse's are. */
    private record Place(String editor) {
        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    /** Eclipse's history as the plug-in sees it, driven as Ctrl-O and Ctrl-I drive it. */
    private static final class History {
        final List<Place> entries = new ArrayList<>();
        int current = -1;
        final HistorySteps<Place, String> steps = new HistorySteps<>();

        /** An editor switch: the user's (a tab click), or the plug-in's for a Neovim jump. */
        Place switchTo(String editor, boolean plugins) {
            while (entries.size() > current + 1) {
                entries.remove(entries.size() - 1); // a new entry deletes the way forward
            }
            Place place = new Place(editor);
            entries.add(place);
            current = entries.size() - 1;
            if (plugins) {
                steps.markOwn(place);
            }
            return place;
        }

        String at() {
            return entries.get(current).editor();
        }

        /** Ctrl-O at the end of the window's jump history. */
        void ctrlO(int count) {
            int n = steps.backSteps(entries, entries.get(current), count);
            if (n > 0) {
                steps.wentBack(entries, entries.get(current), at(), count);
                current -= n;
            }
        }

        /** Ctrl-I at the end of the window's jump history; a restore is the plug-in's switch. */
        void ctrlI(int count) {
            switch (steps.forward(entries, entries.get(current), count)) {
                case HistorySteps.Steps<String> s -> current = Math.min(entries.size() - 1, current + s.steps());
                case HistorySteps.Restore<String> r -> switchTo(r.state(), true);
            }
        }
    }

    @Test
    void backStepsOverThePluginsEntries() {
        History h = new History();
        h.switchTo("a", false);
        h.switchTo("b", true);
        h.switchTo("a", true);
        h.switchTo("c", false);
        h.switchTo("a", true);
        h.ctrlO(0);
        assertEquals(3, h.current, "past the plug-in's a, to the user's c");
        h.ctrlO(0);
        assertEquals(0, h.current, "past b and a, the plug-in's, to the user's a");
        h.ctrlO(0);
        assertEquals(0, h.current, "the start: nowhere to go");
    }

    @Test
    void aCountIsThatManyOfTheUsersEntries() {
        History h = new History();
        h.switchTo("a", false);
        h.switchTo("b", false);
        h.switchTo("x", true);
        h.switchTo("c", false);
        h.ctrlO(2);
        assertEquals("a", h.at());
        h.ctrlI(2);
        assertEquals("c", h.at(), "2 Ctrl-I undoes the 2 Ctrl-O");
    }

    @Test
    void whenTheOldestEntriesAreThePluginsBackStopsOnTheOldest() {
        History h = new History();
        h.switchTo("a", true);
        h.switchTo("b", true);
        h.switchTo("c", false);
        h.ctrlO(0);
        assertEquals(0, h.current);
    }

    @Test
    void forwardRetracesBackThroughEclipsesForward() {
        History h = new History();
        h.switchTo("a", false);
        h.switchTo("b", false);
        h.switchTo("a", true);
        h.switchTo("b", true);
        h.ctrlO(0);
        h.ctrlO(0);
        assertEquals("a", h.at());
        h.ctrlI(0);
        assertEquals(1, h.current);
        h.ctrlI(0);
        assertEquals(3, h.current, "where the first Ctrl-O started, past the plug-in's a");
        h.ctrlI(0);
        assertEquals(3, h.current, "the end");
    }

    /**
     * As reported: Back to an editor whose window has older jumps into another file; Ctrl-O goes
     * on through them, and the plug-in's switches delete Eclipse's way forward. Ctrl-I still comes
     * back to where the Back started, by showing that editor again.
     */
    @Test
    void forwardRestoresAPlaceThatAPluginSwitchDeleted() {
        History h = new History();
        h.switchTo("a", false);
        h.switchTo("c", false); // a tab click
        h.ctrlO(0);
        assertEquals("a", h.at());
        h.switchTo("b", true); // Ctrl-O: a Neovim jump in a's window into b's file
        h.switchTo("a", true); // and Ctrl-I back
        assertEquals(3, h.entries.size(), "c's entry is gone");
        h.ctrlI(0);
        assertEquals("c", h.at(), "shown again");
        assertTrue(h.steps.isOwn(h.entries.get(h.current)), "the restore is the plug-in's switch");
    }

    @Test
    void anEntryTheUserMadeForgetsTheBacks() {
        History h = new History();
        h.switchTo("a", false);
        h.switchTo("b", false);
        h.ctrlO(0);
        h.switchTo("c", false); // Open Declaration, say
        assertTrue(h.steps.remembersBacks());
        h.ctrlI(0);
        assertEquals("c", h.at(), "nowhere forward: c is the newest");
        assertFalse(h.steps.remembersBacks());
    }

    @Test
    void withoutRememberedBacksForwardGoesToTheLastOfThePluginsEntries() {
        History h = new History();
        h.switchTo("a", false);
        h.switchTo("b", false);
        h.switchTo("a", true);
        h.switchTo("b", true);
        h.current = 0; // Eclipse's own Back (Alt+Left), which the plug-in does not see
        h.ctrlI(0);
        assertEquals(1, h.current, "the user's b, one step");
        h.ctrlI(0);
        assertEquals(3, h.current, "the plug-in's two, to the last");
    }

    @Test
    void anUnknownCurrentEntryStepsAsEclipseWould() {
        HistorySteps<Place, String> steps = new HistorySteps<>();
        List<Place> entries = List.of(new Place("a"), new Place("b"));
        assertEquals(1, steps.backSteps(entries, new Place("x"), 0));
        assertEquals(3, steps.backSteps(entries, null, 3));
        assertEquals(new HistorySteps.Steps<String>(2), steps.forward(entries, null, 2));
    }

    @Test
    void aBackThatWentNowhereIsNotUndone() {
        History h = new History();
        h.switchTo("a", false);
        h.ctrlO(0); // the start
        assertFalse(h.steps.remembersBacks());
    }

    @Test
    void entriesEclipseDroppedAreForgotten() {
        History h = new History();
        Place old = h.switchTo("a", true);
        h.switchTo("b", false);
        h.entries.remove(0); // Eclipse keeps 50 entries
        h.current = 0;
        h.ctrlI(0); // drops the note of a's entry
        assertFalse(h.steps.isOwn(old));
    }
}
