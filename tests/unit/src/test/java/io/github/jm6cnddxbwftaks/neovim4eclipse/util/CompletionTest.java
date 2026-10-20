// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Reading the {@code COMPLETE_CMDLINE} answer, as msgpack-rpc delivers it. */
class CompletionTest {

    @Test
    void readsTheLineTheTotalAndTheCandidates() {
        Completion c = Completion.parse(List.of("tabnew", 2L, List.of("tabnew", "tabnext")));
        assertEquals(new Completion("tabnew", List.of("tabnew", "tabnext"), 2), c);
    }

    @Test
    void theTotalCanExceedTheCappedList() {
        Completion c = Completion.parse(List.of("h a", 5000, List.of("a", "b")));
        assertEquals(5000, c.total());
        assertEquals(2, c.candidates().size());
    }

    @Test
    void noAnswerOrAnAnswerNotUnderstoodIsNull() {
        assertNull(Completion.parse(null)); // Neovim was not in normal mode
        assertNull(Completion.parse("tabnew"));
        assertNull(Completion.parse(List.of("tabnew", 2)));
        assertNull(Completion.parse(Arrays.asList(null, 0, List.of())));
    }

    @Test
    void anEmptyCandidateTableIsAnEmptyList() {
        // Lua's {} can arrive as an empty map rather than an empty array.
        assertEquals(List.of(), Completion.parse(List.of("x", 0, Map.of())).candidates());
        assertEquals(List.of(), Completion.parse(List.of("x", 0, List.of())).candidates());
    }

    @Test
    void aMissingTotalIsTheNumberOfCandidates() {
        assertEquals(2, Completion.parse(Arrays.asList("x", null, List.of("a", "b"))).total());
    }
}
