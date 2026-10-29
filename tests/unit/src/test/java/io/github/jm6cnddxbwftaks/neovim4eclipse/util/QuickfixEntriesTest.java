// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Diagnostic.Severity;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.QuickfixEntries.Entry;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.QuickfixEntries.Kind;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.QuickfixEntries.Mode;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.QuickfixEntries.TaskTags;

class QuickfixEntriesTest {

    private static Entry problem(String module, int lnum, int col, Severity severity, String text) {
        return new Entry(0, "/ws/" + module, module, lnum, col, severity, text);
    }

    @Test
    void aSeverityWordListsThatSeverityAndWorse() {
        // Q9: "warning" is at least a warning, as vim.diagnostic's severity = { min = WARN }.
        assertEquals(Severity.INFO, QuickfixEntries.leastSeverity(""));
        assertEquals(Severity.INFO, QuickfixEntries.leastSeverity(null));
        assertEquals(Severity.INFO, QuickfixEntries.leastSeverity("info"));
        assertEquals(Severity.WARN, QuickfixEntries.leastSeverity("warning"));
        assertEquals(Severity.WARN, QuickfixEntries.leastSeverity(" Warn "));
        assertEquals(Severity.ERROR, QuickfixEntries.leastSeverity("error"));
        assertNull(QuickfixEntries.leastSeverity("hint"), "Neovim's fourth severity has no Eclipse problems");

        assertTrue(QuickfixEntries.atLeast(Severity.ERROR, Severity.WARN));
        assertTrue(QuickfixEntries.atLeast(Severity.WARN, Severity.WARN));
        assertFalse(QuickfixEntries.atLeast(Severity.INFO, Severity.WARN));
        assertFalse(QuickfixEntries.atLeast(null, Severity.INFO), "a task is no problem");
    }

    @Test
    void aTaskCountsWhenItsMessageStartsWithATagAsAWord() {
        TaskTags tags = TaskTags.parse(" JM6, FIXME ,,JM6");
        assertEquals(List.of("JM6", "FIXME"), tags.tags(), "trimmed, blanks and repeats dropped");
        assertTrue(tags.matches("JM6 check the offsets"));
        assertTrue(tags.matches("JM6: check"));
        assertTrue(tags.matches("JM6"));
        assertTrue(tags.matches("  FIXME later"), "leading blanks do not matter");
        assertFalse(tags.matches("JM67 other tag"));
        assertFalse(tags.matches("see JM6"));
        assertFalse(tags.matches("jm6 check"), "case matters, as in JDT's default");
        assertFalse(tags.matches("TODO something"));
        assertFalse(tags.matches(null));

        assertTrue(TaskTags.parse("TODO:").matches("TODO:x"), "a tag ending in punctuation needs no word break");
    }

    @Test
    void aStarTakesEveryTaskAndNothingTakesNone() {
        assertTrue(TaskTags.parse("*").matches("TODO anything"));
        assertTrue(TaskTags.parse("JM6, *").all());
        assertTrue(TaskTags.parse("").isEmpty());
        assertTrue(TaskTags.parse(null).isEmpty());
        assertTrue(TaskTags.parse(" , ").isEmpty());
        assertFalse(TaskTags.parse("*").isEmpty());
        assertFalse(TaskTags.parse("").matches("TODO x"));
    }

    @Test
    void theListIsSortedByWorkspacePathThenPlace() {
        List<Entry> sorted = QuickfixEntries.sorted(List.of(
                problem("app/src/b.txt", 3, 0, Severity.WARN, "w"),
                problem("app/src/a.txt", 9, 0, Severity.INFO, "i"),
                problem("App/z.txt", 1, 0, Severity.ERROR, "upper case sorts with lower"),
                problem("app/src/a.txt", 2, 5, Severity.WARN, "w2"),
                problem("app/src/a.txt", 2, 5, Severity.ERROR, "e2"),
                problem("app/src/a.txt", 2, 1, Severity.WARN, "w1")));
        assertEquals(List.of("w1", "e2", "w2", "i", "w", "upper case sorts with lower"),
                sorted.stream().map(Entry::text).toList());
    }

    @Test
    void aFileSeenThroughTwoProjectsIsListedOnce() {
        // A nested project: /ws/app/lib is project "lib" and folder "app/lib" at once.
        Entry viaOuter = new Entry(0, "/ws/app/lib/x.txt", "app/lib/x.txt", 4, 0, Severity.ERROR, "bad");
        Entry viaNested = new Entry(0, "/ws/app/lib/x.txt", "lib/x.txt", 4, 0, Severity.ERROR, "bad");
        Entry other = new Entry(0, "/ws/app/lib/x.txt", "lib/x.txt", 5, 0, Severity.ERROR, "bad");
        assertEquals(List.of(viaNested, other), QuickfixEntries.sorted(List.of(viaOuter, viaNested, other)),
                "the shorter workspace path kept");
    }

    @Test
    void markersOnFoldersOrProjectsWithTheSameTextAreKeptApart() {
        // No file on disk for either: the workspace path tells them apart.
        Entry a = new Entry(0, "", "a", 0, 0, Severity.ERROR, "The project cannot be built until build path errors are resolved");
        Entry b = new Entry(0, "", "b", 0, 0, Severity.ERROR, "The project cannot be built until build path errors are resolved");
        assertEquals(List.of(a, b), QuickfixEntries.sorted(List.of(b, a)));
        assertEquals(List.of(a), QuickfixEntries.sorted(List.of(a, a)), "the same one twice is still one");
    }

    @Test
    void aLineOnlyEntryGivesWayToTheSameProblemAtAColumn() {
        // An open file of a nested project: its editor's problem, and the other IFile's marker.
        Entry exact = new Entry(3, "/ws/app/lib/x.txt", "lib/x.txt", 4, 9, Severity.ERROR, "bad");
        Entry lineOnly = new Entry(0, "/ws/app/lib/x.txt", "app/lib/x.txt", 4, 0, Severity.ERROR, "bad");
        Entry otherLine = new Entry(0, "/ws/app/lib/x.txt", "app/lib/x.txt", 5, 0, Severity.ERROR, "bad");
        Entry otherFile = new Entry(0, "/ws/app/y.txt", "app/y.txt", 4, 0, Severity.ERROR, "bad");
        assertEquals(List.of(otherLine, otherFile, exact), // sorted by workspace path
                QuickfixEntries.sorted(List.of(lineOnly, exact, otherLine, otherFile)));
    }

    @Test
    void anEntryWithoutALineHasNoColumn() {
        Entry entry = new Entry(0, "/ws/a", "a", 0, 7, Severity.ERROR, "x");
        assertEquals(0, entry.col());
        assertEquals(0, new Entry(0, "/ws/a", "a", -3, 0, Severity.ERROR, "x").lnum());
    }

    @Test
    void theTypeIsVimsLetterAndATaskHasNone() {
        assertEquals("E", problem("a", 1, 0, Severity.ERROR, "").type());
        assertEquals("W", problem("a", 1, 0, Severity.WARN, "").type());
        assertEquals("I", problem("a", 1, 0, Severity.INFO, "").type());
        assertEquals("", problem("a", 1, 0, null, "").type());
    }

    @Test
    void luaArgsAreTheHeadThenSevenStringsPerEntryWithTheMessagesFirstLine() {
        List<String> args = QuickfixEntries.toLuaArgs(Kind.PROBLEMS, "warning", Mode.COMMAND, true, List.of(
                new Entry(3, "/ws/app/A.java", "app/A.java", 40, 9, Severity.ERROR, "Type \"mismatch\"\r\nmore"),
                new Entry(0, "/ws/app/B.txt", "app/B.txt", 7, 0, null, "JM6 later")));
        assertEquals(List.of("problems", "warning", "command", "1",
                "3", "/ws/app/A.java", "app/A.java", "40", "9", "E", "Type \"mismatch\"",
                "0", "/ws/app/B.txt", "app/B.txt", "7", "0", "", "JM6 later"), args);
        assertEquals(4 + 2 * QuickfixEntries.LUA_ARGS_PER_ENTRY, args.size());
        assertEquals(List.of("tasks", "", "update", "0"),
                QuickfixEntries.toLuaArgs(Kind.TASKS, null, Mode.UPDATE, false, List.of()));
    }

    @Test
    void theCurrentListIsReadFromNeovimsAnswer() {
        assertEquals(new QuickfixEntries.Current(Kind.PROBLEMS, "warning"),
                QuickfixEntries.Current.fromLua(List.of("problems", "warning")));
        assertEquals(new QuickfixEntries.Current(Kind.TASKS, ""), QuickfixEntries.Current.fromLua(List.of("tasks")));
        assertNull(QuickfixEntries.Current.fromLua(null), "nil: another list is current");
        assertNull(QuickfixEntries.Current.fromLua(List.of()));
        assertNull(QuickfixEntries.Current.fromLua(List.of("vimgrep", "")));
        assertNull(QuickfixEntries.Current.fromLua("problems"));
    }

    @Test
    void kindsAreNamedAsInNeovim() {
        assertEquals(Kind.PROBLEMS, Kind.of("problems"));
        assertEquals(Kind.TASKS, Kind.of("tasks"));
        assertNull(Kind.of("Problems"));
        assertEquals("Eclipse problems", Kind.PROBLEMS.title());
        assertEquals("Eclipse tasks", Kind.TASKS.title());
    }
}
