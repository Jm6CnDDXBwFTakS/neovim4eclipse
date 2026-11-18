// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Which part a host command belongs to, by the window it ran in ({@link CommandOrigin}). */
class CommandOriginTest {

    private static final int NONE = HostCommand.NO_WINDOW;
    private static final int CODE = 1001;
    private static final int PAGE = 1004;
    private static final int OTHER_PAGE = 1005;

    @Test
    void aCommandWithoutAnOriginGoesToTheFocusAsBefore() {
        assertTrue(CommandOrigin.belongsToFocus(NONE, NONE, false));
        assertTrue(CommandOrigin.belongsToFocus(NONE, PAGE, false));
    }

    @Test
    void aPageWithTheFocusTakesOnlyCommandsFromItsOwnWindow() {
        assertTrue(CommandOrigin.belongsToFocus(PAGE, PAGE, true));
        assertFalse(CommandOrigin.belongsToFocus(CODE, PAGE, false), "typed in the code just before the page took the focus");
        assertFalse(CommandOrigin.belongsToFocus(OTHER_PAGE, PAGE, true), "another page's");
    }

    @Test
    void codeWithTheFocusTakesEveryCommandButAPages() {
        assertTrue(CommandOrigin.belongsToFocus(CODE, NONE, false));
        assertTrue(CommandOrigin.belongsToFocus(1000, NONE, false), "the home window, a float over the code");
        assertFalse(CommandOrigin.belongsToFocus(PAGE, NONE, true), ":q typed in a page just before the code took the focus back");
    }
}
