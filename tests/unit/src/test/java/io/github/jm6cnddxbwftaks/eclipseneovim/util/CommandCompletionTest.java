// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.CommandCompletion.ListLine;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.CommandCompletion.Request;

/** The Tab cycle, stale answers, the current candidate and fitting the list into one line. */
class CommandCompletionTest {

    private final CommandCompletion cycle = new CommandCompletion();

    private static Completion answer(String line) {
        return new Completion(line, List.of(), 0);
    }

    // The cycle

    @Test
    void eachTabAddsAStepToTheSameOriginalLine() {
        Request first = cycle.press("tabn", false);
        assertEquals(new Request("tabn", "T", first.generation()), first);
        assertTrue(cycle.accept(first.generation(), answer("tabnew")));
        Request second = cycle.press("tabnew", false);
        assertEquals("tabn", second.line());
        assertEquals("TT", second.steps());
    }

    @Test
    void shiftTabGoesBackAndStartsAtTheLastMatch() {
        assertEquals("TPP", cycle.press("tabn", true).steps());
        Request back = cycle.press("tabn", true); // no answer yet: the box still shows "tabn"
        assertEquals("TPPP", back.steps());

        CommandCompletion other = new CommandCompletion();
        other.press("tabn", false);
        assertEquals("TP", other.press("tabn", true).steps());
    }

    @Test
    void tabsPressedBeforeTheAnswerArrivesContinueTheCycle() {
        Request first = cycle.press("tabn", false);
        Request second = cycle.press("tabn", false);
        assertEquals("TT", second.steps());
        assertFalse(cycle.accept(first.generation(), answer("tabnew")), "the older answer is dropped");
        assertTrue(cycle.accept(second.generation(), answer("tabnext")));
    }

    @Test
    void editingEndsTheCycleAndDropsAnswersOnTheirWay() {
        Request request = cycle.press("tabn", false);
        cycle.reset(); // typed a character
        assertFalse(cycle.accept(request.generation(), answer("tabnew")));
        Request next = cycle.press("tabnx", false);
        assertEquals(new Request("tabnx", "T", next.generation()), next);
    }

    @Test
    void textOtherThanTheLastAnswerStartsANewCycle() {
        Request request = cycle.press("tabn", false);
        cycle.accept(request.generation(), answer("tabnew"));
        Request next = cycle.press("tabnew ", false); // changed without a reset, e.g. by the history
        assertEquals("tabnew ", next.line());
        assertEquals("T", next.steps());
    }

    // The current candidate

    @Test
    void theCurrentCandidateIsTheOneTheLineEndsWith() {
        List<String> candidates = List.of("tabNext", "tabnew", "tabnext");
        assertEquals(1, CommandCompletion.currentIndex("tabnew", candidates));
        assertEquals(2, CommandCompletion.currentIndex("echo 1 | tabnext", candidates));
        assertEquals(-1, CommandCompletion.currentIndex("tabn", candidates), "back at the typed text");
    }

    @Test
    void theLongestWholeWordMatchWins() {
        assertEquals(1, CommandCompletion.currentIndex("e ba.txt", List.of("a.txt", "ba.txt")));
        assertEquals(-1, CommandCompletion.currentIndex("e xa.txt", List.of("a.txt")), "not a whole word");
        assertEquals(0, CommandCompletion.currentIndex("e src/a.txt", List.of("src/a.txt", "src/b.txt")));
        assertEquals(0, CommandCompletion.currentIndex("h :bufdo", List.of(":bufdo", "BufDelete")));
        assertEquals(0, CommandCompletion.currentIndex("set ts=8", List.of("8")));
    }

    @Test
    void escapedSpacesInFileNamesMatch() {
        assertEquals(0, CommandCompletion.currentIndex("e with\\ space.txt", List.of("with space.txt")));
    }

    // The list line

    @Test
    void everythingThatFitsIsShownWithTheCurrentOneMarked() {
        ListLine line = CommandCompletion.fit(List.of("tabNext", "tabnew", "tabnext"), 3, 1, 80);
        assertEquals("tabNext  tabnew  tabnext", line.text());
        assertEquals(9, line.highlightStart());
        assertEquals(6, line.highlightLength());
    }

    @Test
    void noCurrentCandidateMeansNoMark() {
        ListLine line = CommandCompletion.fit(List.of("a", "b"), 2, -1, 80);
        assertEquals("a  b", line.text());
        assertEquals(-1, line.highlightStart());
    }

    @Test
    void aLongListShowsAWindowAroundTheCurrentOne() {
        List<String> letters = List.of("a", "b", "c", "d", "e", "f");
        ListLine line = CommandCompletion.fit(letters, 6, 3, 11);
        assertEquals("… c  d  e …", line.text());
        assertEquals("d", line.text().substring(line.highlightStart(), line.highlightStart() + line.highlightLength()));
        assertEquals("a  b  c …", CommandCompletion.fit(letters, 6, 0, 10).text());
        assertEquals("… d  e  f", CommandCompletion.fit(letters, 6, 5, 10).text());
    }

    @Test
    void aCappedListIsMarkedAsHavingMore() {
        assertEquals("a  b …", CommandCompletion.fit(List.of("a", "b"), 5000, 0, 80).text());
    }

    @Test
    void aCandidateWiderThanTheLineIsStillShown() {
        assertEquals("averyveryverylongname …", CommandCompletion.fit(List.of("averyveryverylongname", "b"), 2, 0, 5).text());
        assertEquals("", CommandCompletion.fit(List.of(), 0, -1, 80).text());
    }
}
