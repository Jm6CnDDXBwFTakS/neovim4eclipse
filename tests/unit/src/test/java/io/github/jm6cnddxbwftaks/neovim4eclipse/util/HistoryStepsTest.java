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
        long nextId = 1;
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

        /** One Ctrl-O step at the end of the window's jump history; its id, or 0 if it went nowhere. */
        long ctrlO() {
            int n = steps.backSteps(entries, entries.get(current));
            if (n == 0) {
                return 0;
            }
            long id = nextId++;
            steps.wentBack(id, entries, entries.get(current), at());
            current -= n;
            return id;
        }

        /** Ctrl-I undoing the Back steps from the newest down to {@code id}; a restore is the plug-in's. */
        void ctrlI(long id) {
            switch (steps.forward(id, entries, entries.get(current))) {
                case HistorySteps.Steps<String> s -> current = Math.min(entries.size() - 1, current + s.steps());
                case HistorySteps.Restore<String> r -> steps.markKnown(switchTo(r.state(), false));
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
        h.ctrlO();
        assertEquals(3, h.current, "past the plug-in's a, to the user's c");
        h.ctrlO();
        assertEquals(0, h.current, "past b and a, the plug-in's, to the user's a");
        assertEquals(0, h.ctrlO(), "the start: nowhere to go");
    }

    @Test
    void whenTheOldestEntriesAreThePluginsBackStopsOnTheOldest() {
        History h = new History();
        h.switchTo("a", true);
        h.switchTo("b", true);
        h.switchTo("c", false);
        h.ctrlO();
        assertEquals(0, h.current);
    }

    @Test
    void forwardRetracesBackThroughEclipsesForward() {
        History h = new History();
        h.switchTo("a", false);
        h.switchTo("b", false);
        h.switchTo("a", true);
        h.switchTo("b", true);
        long first = h.ctrlO();
        long second = h.ctrlO();
        assertEquals("a", h.at());
        h.ctrlI(second);
        assertEquals(1, h.current);
        h.ctrlI(first);
        assertEquals(3, h.current, "where the first Ctrl-O started, past the plug-in's a");
    }

    @Test
    void forwardWithAnOlderIdUndoesEveryStepDownToIt() {
        History h = new History();
        h.switchTo("a", false);
        h.switchTo("b", false);
        h.switchTo("c", false);
        long first = h.ctrlO();
        h.ctrlO();
        h.ctrlI(first); // 2<C-i>
        assertEquals("c", h.at());
        assertEquals(0, h.steps.remembered());
    }

    /**
     * Back to an editor whose window has older jumps into another file; Ctrl-O goes on through them,
     * and the plug-in's switches delete Eclipse's way forward. Ctrl-I still comes back to where the
     * Back started, by showing that editor again.
     */
    @Test
    void forwardRestoresAPlaceThatAPluginSwitchDeleted() {
        History h = new History();
        h.switchTo("a", false);
        h.switchTo("c", false); // a tab click
        long back = h.ctrlO();
        assertEquals("a", h.at());
        h.switchTo("b", true); // Ctrl-O: a Neovim jump in a's window into b's file
        h.switchTo("a", true); // and Ctrl-I back
        assertEquals(3, h.entries.size(), "c's entry is gone");
        h.ctrlI(back);
        assertEquals("c", h.at(), "shown again");
        assertFalse(h.steps.userMovedOn(h.entries), "the restore is no entry of the user's");
    }

    @Test
    void anEntryTheUserMadeForgetsTheBacks() {
        History h = new History();
        h.switchTo("a", false);
        h.switchTo("b", false);
        long back = h.ctrlO();
        h.switchTo("c", false); // Open Declaration, say
        assertTrue(h.steps.userMovedOn(h.entries), "Neovim's trail is to forget its steps too");
        h.ctrlI(back);
        assertEquals("c", h.at(), "nothing to undo");
    }

    @Test
    void theIdOfAStepThatWentNowhereMovesNothing() {
        History h = new History();
        h.switchTo("a", false);
        h.switchTo("b", false);
        long back = h.ctrlO();
        h.steps.wentNowhere(back); // nothing visible changed
        h.ctrlI(back);
        assertEquals("a", h.at());
        h.ctrlI(99); // an id Eclipse never saw (Neovim's trail outlived ours)
        assertEquals("a", h.at());
    }

    @Test
    void plainForwardGoesToTheLastOfThePluginsEntriesAndForgetsTheBacks() {
        History h = new History();
        h.switchTo("a", false);
        h.switchTo("b", false);
        h.switchTo("a", true);
        h.switchTo("b", true);
        h.ctrlO();
        h.current = 0;
        h.current += h.steps.plainForwardSteps(h.entries, h.entries.get(h.current), 0);
        assertEquals(1, h.current, "the user's b, one step");
        h.current += h.steps.plainForwardSteps(h.entries, h.entries.get(h.current), 0);
        assertEquals(3, h.current, "the plug-in's two, to the last");
        assertEquals(0, h.steps.remembered());
    }

    @Test
    void anUnknownCurrentEntryStepsAsEclipseWould() {
        HistorySteps<Place, String> steps = new HistorySteps<>();
        List<Place> entries = List.of(new Place("a"), new Place("b"));
        assertEquals(1, steps.backSteps(entries, new Place("x")));
        assertEquals(1, steps.backSteps(entries, null));
        assertEquals(3, steps.plainForwardSteps(entries, null, 3));
    }

    @Test
    void entriesEclipseDroppedAreForgotten() {
        History h = new History();
        Place old = h.switchTo("a", true);
        h.switchTo("b", false);
        h.entries.remove(0); // Eclipse keeps 50 entries
        h.current = 0;
        h.steps.userMovedOn(h.entries);
        assertFalse(h.steps.isOwn(old));
    }

    @Test
    void atMostMaxStepsAreRemembered() {
        HistorySteps<Place, String> steps = new HistorySteps<>();
        List<Place> entries = List.of(new Place("a"));
        for (long id = 1; id <= HistorySteps.MAX_STEPS + 5; id++) {
            steps.wentBack(id, entries, entries.get(0), "a");
        }
        assertEquals(HistorySteps.MAX_STEPS, steps.remembered());
    }
}
