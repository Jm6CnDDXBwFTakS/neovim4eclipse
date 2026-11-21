// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.EnterDecision.Action;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.EnterDecision.Enter;

/** What Eclipse does when Neovim enters a buffer ({@link EnterDecision}), row by row of its table. */
class EnterDecisionTest {

    private static final int NONE = EnterDecision.NONE;
    private static final int CODE_WIN = 1001;
    private static final int CODE_BUF = 3;
    private static final int PAGE_WIN = 1004;
    private static final int PAGE_BUF = 9;
    private static final int OTHER_DOC = 5;
    private static final int SCRATCH = 12;

    /** Neovim entered {@code buffer} in {@code window}; the page with the focus is {@code focused}. */
    private static Action entered(int window, int buffer, int focused) {
        boolean page = window == PAGE_WIN;
        return EnterDecision.decide(new Enter(window, buffer, CODE_WIN, CODE_BUF, focused, page ? PAGE_BUF : NONE,
                buffer == CODE_BUF || buffer == OTHER_DOC, buffer == 20, false));
    }

    /** Neovim entered {@code buffer} in a plug-in's float just opened with the focus (window 1009), a picker's prompt. */
    private static Action inNewFloat(int buffer) {
        return EnterDecision.decide(new Enter(1009, buffer, CODE_WIN, CODE_BUF, NONE, NONE,
                buffer == CODE_BUF || buffer == OTHER_DOC, buffer == 20, true));
    }

    /**
     * A float just opened with the focus (a picker's prompt) keeps Neovim, so that the insert its
     * plug-in starts begins there; a document or a project file shown in it goes the usual way
     * (any window but the attached one is sent back).
     */
    @Test
    void aNewFloatKeepsNeovimButNotADocumentOrAFile() {
        assertEquals(Action.ACCEPT, inNewFloat(SCRATCH));
        assertEquals(Action.RETURN_BACK, inNewFloat(OTHER_DOC));
        assertEquals(Action.RETURN_BACK, inNewFloat(CODE_BUF));
        assertEquals(Action.RETURN_BACK, inNewFloat(20));
    }

    @Test
    void inThePagesWindowItsOwnBufferIsTheFocusMoving() {
        assertEquals(Action.ACCEPT, entered(PAGE_WIN, PAGE_BUF, PAGE_WIN));
        assertEquals(Action.RETURN_BACK, entered(PAGE_WIN, PAGE_BUF, NONE), "Neovim went into the page by itself");
    }

    @Test
    void thePagesWindowNeverKeepsAnotherBuffer() {
        assertEquals(Action.PAGE_BACK_THEN_FOLLOW, entered(PAGE_WIN, OTHER_DOC, PAGE_WIN), "gf onto an open file");
        assertEquals(Action.PAGE_BACK_THEN_FOLLOW, entered(PAGE_WIN, CODE_BUF, PAGE_WIN), "the code it came from");
        assertEquals(Action.PAGE_BACK_THEN_OPEN_FILE, entered(PAGE_WIN, 20, PAGE_WIN), "gf onto a file without an editor");
        assertEquals(Action.PAGE_BACK, entered(PAGE_WIN, SCRATCH, PAGE_WIN), ":b of a scratch buffer");
    }

    @Test
    void inTheAttachedWindowAsBeforePages() {
        assertEquals(Action.ACCEPT, entered(CODE_WIN, CODE_BUF, NONE), "Eclipse's own switch");
        assertEquals(Action.ACCEPT, entered(CODE_WIN, CODE_BUF, PAGE_WIN), "an older notice does not end a page's focus");
        assertEquals(Action.FOLLOW, entered(CODE_WIN, OTHER_DOC, NONE));
        assertEquals(Action.OPEN_FILE, entered(CODE_WIN, 20, NONE));
        assertEquals(Action.RETURN_BACK, entered(CODE_WIN, SCRATCH, NONE), "help, a scratch buffer");
        assertEquals(Action.FOLLOW, entered(NONE, OTHER_DOC, NONE), "no window named: the attached one, as before");
    }

    @Test
    void anyOtherWindowIsLeft() {
        assertEquals(Action.RETURN_BACK, entered(1000, CODE_BUF, NONE), "the home window after a :tabclose");
        assertEquals(Action.RETURN_BACK, entered(1002, OTHER_DOC, NONE), "another editor's window, entered by :tabnext");
    }
}
