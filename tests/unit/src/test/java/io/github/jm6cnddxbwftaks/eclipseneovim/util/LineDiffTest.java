// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.LineDiff.Change;

class LineDiffTest {

    /** Applies a change the way {@code nvim_buf_set_lines(start, end, replacement)} does. */
    private static List<String> apply(List<String> lines, Change change) {
        List<String> out = new ArrayList<>(lines.subList(0, change.start()));
        out.addAll(change.replacement());
        out.addAll(lines.subList(change.end(), lines.size()));
        return out;
    }

    @Test
    void equalListsNeedNoChange() {
        assertNull(LineDiff.between(List.of("a", "b"), List.of("a", "b")));
        assertNull(LineDiff.between(List.of(), List.of()));
    }

    @Test
    void oneEditedLineReplacesOnlyThatLine() {
        assertEquals(new Change(1, 2, List.of("B")), LineDiff.between(List.of("a", "b", "c"), List.of("a", "B", "c")));
    }

    @Test
    void insertionIsAnEmptyRange() {
        assertEquals(new Change(1, 1, List.of("x")), LineDiff.between(List.of("a", "b"), List.of("a", "x", "b")));
    }

    @Test
    void deletionHasAnEmptyReplacement() {
        assertEquals(new Change(1, 2, List.of()), LineDiff.between(List.of("a", "x", "b"), List.of("a", "b")));
    }

    @Test
    void repeatedLinesDoNotLetPrefixAndSuffixOverlap() {
        assertEquals(new Change(1, 2, List.of()), LineDiff.between(List.of("a", "a"), List.of("a")));
        assertEquals(new Change(1, 1, List.of("a")), LineDiff.between(List.of("a"), List.of("a", "a")));
    }

    @Test
    void randomEditsAlwaysReproduceTheNewLines() {
        Random random = new Random(42);
        for (int round = 0; round < 2000; round++) {
            List<String> before = randomLines(random);
            List<String> after = randomLines(random);
            Change change = LineDiff.between(before, after);
            assertEquals(after, change == null ? before : apply(before, change), before + " -> " + after);
        }
    }

    private static List<String> randomLines(Random random) {
        List<String> lines = new ArrayList<>();
        int count = random.nextInt(6);
        for (int i = 0; i < count; i++) {
            lines.add(String.valueOf((char) ('a' + random.nextInt(3))));
        }
        return lines;
    }
}
