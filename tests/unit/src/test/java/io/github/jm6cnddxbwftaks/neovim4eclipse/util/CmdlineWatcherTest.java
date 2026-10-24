// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CmdlineWatcher.Decision;

/** Which command lines the user opened in Neovim, and which the plug-in typed itself. */
class CmdlineWatcherTest {

    private final CmdlineWatcher watcher = new CmdlineWatcher();
    private long now = 1_000;

    private Decision show(String firstc, String content) {
        return watcher.onEvent(new CmdlineEvent(true, firstc, content, 1), now);
    }

    private void hide() {
        watcher.onEvent(new CmdlineEvent(false, "", "", 1), now);
    }

    @Test
    void aLineTheUserOpenedIsReportedOnce() {
        assertEquals(Decision.USER_LINE, show(":", ""));
        assertEquals(Decision.IGNORE, show(":", "w"), "the same line with more text");
        hide();
        assertEquals(Decision.USER_LINE, show("/", ""), "a new line after the hide");
    }

    @Test
    void thePluginsOwnLineIsIgnored() {
        watcher.expectOwn(":", now);
        assertEquals(Decision.IGNORE, show(":", "silent ls"));
        hide();
        assertEquals(Decision.USER_LINE, show(":", ""), "the expectation was used up");
    }

    @Test
    void ownLinesAreMatchedInOrderByType() {
        watcher.expectOwn("/", now);
        watcher.expectOwn(":", now);
        assertEquals(Decision.IGNORE, show("/", "foo"));
        hide();
        assertEquals(Decision.IGNORE, show(":", "silent echo 1"));
        hide();
    }

    @Test
    void anExpectationNoLineFulfilsExpires() {
        watcher.expectOwn(":", now);
        now += CmdlineWatcher.OWN_LINE_TIMEOUT_MS + 1;
        assertEquals(Decision.USER_LINE, show(":", ""));
    }

    /** Tab completion types into a line inside one Lua call: Neovim reports only the hide. */
    @Test
    void aHideWithoutAShowChangesNothing() {
        hide();
        assertEquals(Decision.USER_LINE, show(":", ""));
    }

    @Test
    void promptsAndNestedLinesAreIgnored() {
        assertEquals(Decision.IGNORE, show("", "Name: "), "an input() prompt");
        hide();
        assertEquals(Decision.IGNORE, watcher.onEvent(new CmdlineEvent(true, "=", "1+1", 2), now), "<C-r>= inside a line");
    }
}
