// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * {@link KeyContextBatch}: which key contexts are active, and that a change of them is one batch of
 * only the difference. A fake {@link KeyContextBatch.Switch} records the calls; that the contexts
 * really switch the workbench's key bindings is {@code VimKeyContextsTest} in the UI tests.
 */
class KeyContextBatchTest {

    private final List<String> calls = new ArrayList<>();
    private final KeyContextBatch<String> batch = new KeyContextBatch<>();

    @Test
    void theWantedContextsAreTheParentAndTheChosenLetters() {
        Set<String> wanted = KeyContextBatch.wanted(letter -> letter != 's', VimCtrlKeys::contextId);
        assertTrue(wanted.contains(VimCtrlKeys.NORMAL_CONTEXT_ID));
        assertTrue(wanted.contains(VimCtrlKeys.contextId('v')));
        assertFalse(wanted.contains(VimCtrlKeys.contextId('s')));
        assertEquals(VimCtrlKeys.LETTERS.length(), wanted.size(), "every letter but s, and the parent");
    }

    /** The context a letter gets can be another than its own, as Word Completion's in an insert. */
    @Test
    void aLetterCanBringAnotherContext() {
        Set<String> wanted = KeyContextBatch.wanted(letter -> letter == 'n', letter -> "word.completion");
        assertEquals(Set.of(VimCtrlKeys.NORMAL_CONTEXT_ID, "word.completion"), wanted);
    }

    /**
     * The whole switch is one batch ({@code deferUpdates}), so the workbench recomputes its key
     * bindings once instead of once per context: 5 to 9 ms at every Esc before.
     */
    @Test
    void activatingIsOneBatch() {
        batch.apply(recording(-1), Set.of("parent", "v", "r"));
        assertEquals(List.of("deferUpdates true", "activate", "activate", "activate", "deferUpdates false"), calls);
        assertTrue(batch.isActive());
        assertEquals(Set.of("parent", "v", "r"), batch.activeIds());
    }

    @Test
    void deactivatingIsOneBatch() {
        KeyContextBatch.Switch<String> fake = recording(-1);
        batch.apply(fake, Set.of("parent", "v"));
        calls.clear();
        batch.deactivate(fake);
        assertEquals(List.of("deferUpdates true", "deactivate [token parent, token v]", "deferUpdates false"), calls);
        assertFalse(batch.isActive());
    }

    @Test
    void deactivatingWhenNothingIsActiveDoesNothing() {
        batch.deactivate(recording(-1));
        assertEquals(List.of(), calls);
    }

    /**
     * Insert mode's letters replace normal mode's: only the letters whose two choices differ change,
     * in one batch. Here {@code v} goes off, {@code w} comes on, {@code r} and the parent stay.
     */
    @Test
    void switchingLettersChangesOnlyTheDifference() {
        KeyContextBatch.Switch<String> fake = recording(-1);
        batch.apply(fake, Set.of("parent", "v", "r"));
        calls.clear();
        batch.apply(fake, Set.of("parent", "w", "r"));
        assertEquals(List.of("deferUpdates true", "deactivate [token v]", "activate", "deferUpdates false"), calls);
        assertEquals(Set.of("parent", "w", "r"), batch.activeIds());
        calls.clear();
        batch.apply(fake, Set.of("parent", "w", "r"));
        assertEquals(List.of(), calls, "nothing to change, no batch");
    }

    /** A failure must not leave the workbench holding back every later context change. */
    @Test
    void aFailingActivationStillEndsTheBatch() {
        KeyContextBatch.Switch<String> failing = recording(2);
        assertThrows(IllegalStateException.class, () -> batch.apply(failing, Set.of("parent", "v", "r", "w")));
        assertEquals("deferUpdates false", calls.get(calls.size() - 1));
        // The two activated before the failure are remembered, so a later deactivate releases them.
        assertEquals(2, batch.activeIds().size());
        calls.clear();
        batch.deactivate(failing);
        assertEquals(2, calls.stream().filter(c -> c.startsWith("deactivate [")).findFirst()
                .map(c -> c.split(",").length).orElse(0), "both tokens handed back: " + calls);
        assertFalse(batch.isActive());
    }

    /**
     * A {@link KeyContextBatch.Switch} that records the calls; an activation's token is
     * {@code "token <id>"}, and a deactivation lists its tokens sorted (a batch's order is a
     * {@code Set}'s). The activation numbered {@code failAt} (0-based) throws; -1 never does.
     */
    private KeyContextBatch.Switch<String> recording(int failAt) {
        int[] activations = { 0 };
        return new KeyContextBatch.Switch<>() {
            @Override
            public void deferUpdates(boolean defer) {
                calls.add("deferUpdates " + defer);
            }

            @Override
            public String activate(String contextId) {
                calls.add("activate");
                if (activations[0]++ == failAt) {
                    throw new IllegalStateException("activation fails");
                }
                return "token " + contextId;
            }

            @Override
            public void deactivate(Collection<String> tokens) {
                calls.add("deactivate " + tokens.stream().sorted().toList());
            }
        };
    }
}
