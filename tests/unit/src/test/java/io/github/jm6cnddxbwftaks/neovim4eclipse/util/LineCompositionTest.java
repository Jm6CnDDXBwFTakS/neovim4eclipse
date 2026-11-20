// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link LineComposition}: an input method's composition on Neovim's command line, as decisions. */
class LineCompositionTest {

    private final LineComposition line = new LineComposition();

    /** Japanese "ka", Space for the candidate 可, Enter: nothing is sent until the commit, then 可 once. */
    @Test
    void aCompositionIsSentOnceAtItsCommit() {
        assertEquals("", line.changed("k", 0));
        assertEquals("k", line.composing());
        assertEquals("", line.changed("か", 0));
        assertEquals("", line.changed("可", 0));
        assertEquals("可", line.composing());
        assertEquals("可", line.changed("可", 1));
        assertEquals("", line.composing(), "nothing composing after the commit");
    }

    /** The committed character comes again as a key press (on macOS with Enter's key code): dropped, once. */
    @Test
    void theKeyPressThatRepeatsTheCommitIsDropped() {
        line.changed("可", 1);
        assertTrue(line.dropKey('可'));
        assertFalse(line.dropKey('可'), "a second one is a key of its own");
    }

    /** A commit of two characters: both key presses are dropped, in order. */
    @Test
    void eachCommittedCharactersKeyIsDropped() {
        assertEquals("可能", line.changed("可能", 2));
        assertTrue(line.dropKey('可'));
        assertTrue(line.dropKey('能'));
        assertFalse(line.dropKey('x'));
    }

    /** A system that sends no key press after the commit: the next key is an ordinary one, not swallowed. */
    @Test
    void anotherKeyEndsTheExpectedEcho() {
        line.changed("可", 1);
        assertFalse(line.dropKey('x'), "an ordinary key");
        assertFalse(line.dropKey('可'), "the echo is past: this one is typed");
    }

    /** Part of the text committed, the rest still composing (a phrase converted in pieces). */
    @Test
    void aPartialCommitSendsItsPartAndKeepsTheRestComposing() {
        assertEquals("日本", line.changed("日本ご", 2));
        assertEquals("ご", line.composing());
    }

    /** No key presses and nothing composing before any composition, or after one ended. */
    @Test
    void anEndedCompositionLeavesNothing() {
        assertFalse(line.dropKey('a'));
        line.changed("か", 0);
        line.end();
        assertEquals("", line.composing());
        assertFalse(line.dropKey('か'));
        assertEquals("", line.changed(null, 0), "a missing text is none");
    }
}
