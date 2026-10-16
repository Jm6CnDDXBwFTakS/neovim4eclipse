// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.CommandLine.Kind;

import org.junit.jupiter.api.Test;

class CommandHistoryTest {

    private static CommandHistory sample() {
        CommandHistory h = new CommandHistory(10);
        h.add(Kind.EX, "one");
        h.add(Kind.EX, "two");
        h.add(Kind.EX, "three");
        return h;
    }

    @Test
    void previousWalksBackAndStopsAtTheOldest() {
        CommandHistory h = sample();
        assertEquals("three", h.previous(Kind.EX, ""));
        assertEquals("two", h.previous(Kind.EX, ""));
        assertEquals("one", h.previous(Kind.EX, ""));
        assertEquals("one", h.previous(Kind.EX, ""), "stays on the oldest entry");
    }

    @Test
    void nextWalksForwardAndFinallyRestoresTheDraft() {
        CommandHistory h = sample();
        h.previous(Kind.EX, "half-typed");
        h.previous(Kind.EX, "ignored while navigating");
        assertEquals("three", h.next(Kind.EX));
        assertEquals("half-typed", h.next(Kind.EX), "back past the newest entry: what was being typed");
        assertNull(h.next(Kind.EX), "nothing further");
    }

    @Test
    void nextWithoutNavigationDoesNothing() {
        assertNull(sample().next(Kind.EX));
    }

    @Test
    void emptyHistoryHasNothingToOffer() {
        assertNull(new CommandHistory(5).previous(Kind.EX, "x"));
    }

    @Test
    void kindsHaveSeparateHistories() {
        CommandHistory h = new CommandHistory(10);
        h.add(Kind.EX, "w");
        h.add(Kind.SEARCH_FORWARD, "foo");
        assertEquals("w", h.previous(Kind.EX, ""));
        assertEquals("foo", h.previous(Kind.SEARCH_FORWARD, ""));
        assertNull(h.previous(Kind.SEARCH_BACKWARD, ""));
    }

    @Test
    void duplicatesMoveToTheEndAndBlanksAreIgnored() {
        CommandHistory h = sample();
        h.add(Kind.EX, "one");
        h.add(Kind.EX, "  ");
        h.add(Kind.EX, null);
        assertEquals("one", h.previous(Kind.EX, ""));
        assertEquals("three", h.previous(Kind.EX, ""));
        assertEquals("two", h.previous(Kind.EX, ""));
        assertEquals("two", h.previous(Kind.EX, ""), "three entries only: no second 'one'");
    }

    @Test
    void oldEntriesAreDroppedBeyondTheLimit() {
        CommandHistory h = new CommandHistory(2);
        h.add(Kind.EX, "a");
        h.add(Kind.EX, "b");
        h.add(Kind.EX, "c");
        assertEquals("c", h.previous(Kind.EX, ""));
        assertEquals("b", h.previous(Kind.EX, ""));
        assertEquals("b", h.previous(Kind.EX, ""), "'a' was dropped");
    }

    @Test
    void addingResetsNavigation() {
        CommandHistory h = sample();
        h.previous(Kind.EX, "draft");
        h.add(Kind.EX, "four");
        assertEquals("four", h.previous(Kind.EX, ""));
    }

    @Test
    void resetNavigationStartsFromTheNewestAgain() {
        CommandHistory h = sample();
        h.previous(Kind.EX, "");
        h.previous(Kind.EX, "");
        h.resetNavigation(Kind.EX);
        assertEquals("three", h.previous(Kind.EX, ""));
    }
}
