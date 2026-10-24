// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.KeyGrammar.Expect;

/**
 * What the next key is after typing a sequence, without Neovim. The expectations are what Neovim
 * 0.12 reported for the same keys (docs/design/KEY_ROUTING.md, experiments X1); the comparison with
 * a running Neovim is {@code KeyGrammarNeovimTest} (integration profile).
 */
class KeyGrammarTest {

    /** Feeds space-separated keys ({@code "d i \""}), not recording, and returns the grammar. */
    private static KeyGrammar typed(String keys) {
        KeyGrammar grammar = new KeyGrammar();
        if (!keys.isBlank()) {
            for (String key : keys.split(" ")) {
                grammar.feed(key, false);
            }
        }
        return grammar;
    }

    @ParameterizedTest(name = "after [{0}] the next key is {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '~', textBlock = """
            ~~              | COMMAND
            # operators wait for a motion, text object or themselves
            d               | MOTION
            d w             | COMMAND
            d d             | COMMAND
            d 3             | MOTION
            d 3 j           | COMMAND
            d 1 0 j         | COMMAND
            d v             | MOTION
            d v j           | COMMAND
            d Z             | COMMAND
            <lt> <lt>       | COMMAND
            > >             | COMMAND
            = =             | COMMAND
            ! !             | COMMAND
            # text objects: the key after i/a is a name, not a command taking an argument
            d i             | ARGUMENT
            d i "           | COMMAND
            y i '           | COMMAND
            d i `           | COMMAND
            y a (           | COMMAND
            y 2 i w         | COMMAND
            d a w           | COMMAND
            # g, z and their operators
            g               | ARGUMENT
            g g             | COMMAND
            g J             | COMMAND
            g q             | MOTION
            g q q           | COMMAND
            g ?             | MOTION
            g ? ?           | COMMAND
            g ? g ?         | COMMAND
            g U U           | COMMAND
            g U g U         | COMMAND
            g ~ ~           | COMMAND
            g w w           | COMMAND
            g @             | MOTION
            g '             | ARGUMENT
            g ' a           | COMMAND
            g `             | ARGUMENT
            g r             | ARGUMENT
            d g             | ARGUMENT
            d g g           | COMMAND
            d g j           | COMMAND
            z               | ARGUMENT
            z z             | COMMAND
            z f             | MOTION
            z f j           | COMMAND
            z f f           | ARGUMENT
            z f z           | ARGUMENT
            z f z f         | COMMAND
            d z j           | COMMAND
            # other prefixes
            [               | ARGUMENT
            [ /             | COMMAND
            ] q             | COMMAND
            d [             | ARGUMENT
            d [ /           | COMMAND
            y [ /           | COMMAND
            d ] '           | COMMAND
            <C-w>           | ARGUMENT
            <C-w> q         | COMMAND
            <C-w> <C-w>     | COMMAND
            Z               | ARGUMENT
            # commands taking one key
            f               | ARGUMENT
            f :             | COMMAND
            t /             | COMMAND
            r ?             | COMMAND
            m q             | COMMAND
            @ q             | COMMAND
            d t "           | COMMAND
            # registers, counts
            "               | ARGUMENT
            " a             | COMMAND
            " q y y         | COMMAND
            2 " a y y       | COMMAND
            1 0 j           | COMMAND
            # inserts
            o               | TEXT
            s               | TEXT
            c c             | TEXT
            c i "           | TEXT
            c t x           | TEXT
            g i             | TEXT
            o x <Esc>       | COMMAND
            # visual mode
            v               | COMMAND
            v i             | ARGUMENT
            v i "           | COMMAND
            v a "           | COMMAND
            v i (           | COMMAND
            <C-v> i w       | COMMAND
            v g ?           | COMMAND
            v g ? ?         | COMMAND
            v r             | ARGUMENT
            v c             | TEXT
            v q             | ARGUMENT
            """)
    void nextKey(String keys, Expect expected) {
        assertEquals(expected, typed(keys).expect());
    }

    @Test
    void visualModeFollowsTheKeys() {
        assertTrue(typed("v").isVisual());
        assertTrue(typed("v i \"").isVisual(), "a text object extends the selection");
        assertTrue(typed("v o").isVisual(), "o moves to the other end");
        assertTrue(typed("v V").isVisual(), "V switches to linewise");
        assertFalse(typed("v v").isVisual(), "the same key ends it");
        assertFalse(typed("v d").isVisual());
        assertFalse(typed("v g ?").isVisual(), "g? encodes the selection at once");
        assertFalse(typed("v r x").isVisual(), "r replaces the selection");
        assertFalse(typed("v <Esc>").isVisual());
    }

    @Test
    void qStartsARecordingOnlyAsACommand() {
        assertTrue(typed("q").expectsRecordingRegister());
        assertTrue(typed("v q").expectsRecordingRegister());
        assertTrue(typed("\" a q").expectsRecordingRegister(), "a register name before q is allowed");
        assertFalse(typed("g q q").expectsRecordingRegister(), "gqq formats the line");
        assertFalse(typed("<C-w> q").expectsRecordingRegister(), "<C-w>q closes a window");
        assertFalse(typed("m q").expectsRecordingRegister(), "mq sets mark q");
        assertFalse(typed("@ q").expectsRecordingRegister(), "@q plays register q");
        assertFalse(typed("\" q").expectsRecordingRegister(), "\"q names register q");
    }

    @Test
    void qWhileRecordingStopsIt() {
        KeyGrammar grammar = new KeyGrammar();
        grammar.feed("q", true);
        assertEquals(Expect.COMMAND, grammar.expect());
        assertFalse(grammar.expectsRecordingRegister());
    }

    @Test
    void questionDoublesOnlyTheRot13Operator() {
        assertTrue(typed("g ?").questionDoublesOperator());
        assertTrue(typed("2 g ?").questionDoublesOperator());
        assertFalse(typed("d").questionDoublesOperator(), "d? searches backward");
        assertFalse(typed("g ? ?").questionDoublesOperator(), "the operator is done");
        assertFalse(typed("v g ?").questionDoublesOperator(), "in visual mode g? applies at once");
    }

    // ---- syncTo: Neovim's answer replaces the guess ----

    @Test
    void neovimsBlockingMeansTheNextKeyIsAnArgument() {
        KeyGrammar grammar = typed("<Space>"); // a mapping prefix the grammar does not know
        assertEquals(Expect.COMMAND, grammar.expect());
        grammar.syncTo("n", true);
        assertEquals(Expect.ARGUMENT, grammar.expect());
    }

    @Test
    void neovimsNormalModeEndsAWrongGuess() {
        KeyGrammar grammar = typed("d");
        grammar.syncTo("n", false); // the operator was cancelled (an Esc Eclipse took, say)
        assertEquals(Expect.COMMAND, grammar.expect());
    }

    @Test
    void neovimsOperatorPendingKeepsTheOperatorTheKeysNamed() {
        KeyGrammar grammar = typed("g ?");
        grammar.syncTo("no", false);
        assertTrue(grammar.questionDoublesOperator(), "Neovim does not say which operator; the keys do");
    }

    @Test
    void anOperatorTheKeysMissedIsStillAnOperator() {
        KeyGrammar grammar = new KeyGrammar();
        grammar.syncTo("no", false);
        assertEquals(Expect.MOTION, grammar.expect());
        assertFalse(grammar.questionDoublesOperator(), "unknown, so ? is taken as a search");
        grammar.feed("w", false);
        assertEquals(Expect.COMMAND, grammar.expect());
    }

    @Test
    void neovimsInsertAndVisualModesAreTaken() {
        KeyGrammar grammar = new KeyGrammar();
        grammar.syncTo("i", false);
        assertEquals(Expect.TEXT, grammar.expect());
        grammar.syncTo("V", false);
        assertEquals(Expect.COMMAND, grammar.expect());
        assertTrue(grammar.isVisual());
        grammar.feed("V", false);
        assertFalse(grammar.isVisual(), "V ends the linewise visual mode Neovim reported");
    }

    @Test
    void otherModesLeaveTheGuess() {
        KeyGrammar grammar = typed("d");
        grammar.syncTo("c", false);
        assertEquals(Expect.MOTION, grammar.expect());
    }

    @Test
    void aPromptEndsThePendingCommand() {
        KeyGrammar grammar = typed("d");
        grammar.promptOpened();
        assertEquals(Expect.COMMAND, grammar.expect());
    }
}
