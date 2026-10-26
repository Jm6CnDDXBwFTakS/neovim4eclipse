// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CandidateLine.ListLine;

/** Fitting Neovim's completion candidates into the one line above the {@code :} box. */
class CandidateLineTest {

    @Test
    void everythingThatFitsIsShownWithTheSelectedOneMarked() {
        ListLine line = CandidateLine.fit(List.of("tabNext", "tabnew", "tabnext"), 1, 80);
        assertEquals("tabNext  tabnew  tabnext", line.text());
        assertEquals(9, line.highlightStart());
        assertEquals(6, line.highlightLength());
    }

    /** Neovim selects nothing when the line holds what was typed (Tab, then back with Shift+Tab). */
    @Test
    void noSelectionMeansNoMark() {
        ListLine line = CandidateLine.fit(List.of("a", "b"), -1, 80);
        assertEquals("a  b", line.text());
        assertEquals(-1, line.highlightStart());
    }

    @Test
    void aLongListShowsAWindowAroundTheSelectedOne() {
        List<String> letters = List.of("a", "b", "c", "d", "e", "f");
        ListLine line = CandidateLine.fit(letters, 3, 11);
        assertEquals("… c  d  e …", line.text());
        assertEquals("d", line.text().substring(line.highlightStart(), line.highlightStart() + line.highlightLength()));
        assertEquals("a  b  c …", CandidateLine.fit(letters, 0, 10).text());
        assertEquals("… d  e  f", CandidateLine.fit(letters, 5, 10).text());
    }

    @Test
    void aCandidateWiderThanTheLineIsStillShown() {
        assertEquals("averyveryverylongname …", CandidateLine.fit(List.of("averyveryverylongname", "b"), 0, 5).text());
        assertEquals("", CandidateLine.fit(List.of(), -1, 80).text());
    }
}
