// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link LinkedEditInsert}: the end of a linked edit ends insert mode only when the insert began
 * inside it (a rename) and Enter accepted it.
 */
class LinkedEditInsertTest {

    private final LinkedEditInsert decision = new LinkedEditInsert();

    /** Alt+Shift+R in normal mode, then {@code ea} and a suffix: the Enter that accepts it ends the insert. */
    @Test
    void anInsertBegunInARenameEndsWhenEnterAcceptsIt() {
        decision.insertBegan(true);
        assertTrue(decision.linkedEditEnded(true));
    }

    /** A click elsewhere ends the rename; the insert goes on, as after a click in Vim. */
    @Test
    void aRenameEndedOtherwiseKeepsTheInsert() {
        decision.insertBegan(true);
        assertFalse(decision.linkedEditEnded(false));
    }

    /** A template inserted while typing: its Enter leaves the fields, and the typing goes on. */
    @Test
    void anInsertBegunBeforeALinkedEditGoesOnAfterItsEnter() {
        decision.insertBegan(false);
        assertFalse(decision.linkedEditEnded(true));
    }

    /** After the insert ended (Esc), the end of the linked edit ends nothing. */
    @Test
    void nothingIsRememberedOnceTheInsertEnded() {
        decision.insertBegan(true);
        decision.insertEnded();
        assertFalse(decision.linkedEditEnded(true));
    }

    /** The answer is given once: a second end reported for the same insert ends nothing. */
    @Test
    void theAnswerIsGivenOnce() {
        decision.insertBegan(true);
        assertTrue(decision.linkedEditEnded(true));
        assertFalse(decision.linkedEditEnded(true));
    }
}
