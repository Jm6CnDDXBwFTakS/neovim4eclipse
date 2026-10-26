// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Telling the plug-in's own typed lines ({@code commandAsync}) from every other command line. */
class CmdlineWatcherTest {

    private final CmdlineWatcher watcher = new CmdlineWatcher();
    private final long now = 1_000_000;

    private static CmdlineEvent show(String firstc, String content) {
        return new CmdlineEvent.Show(firstc, content, content.length(), "", 0, 1);
    }

    private static final CmdlineEvent HIDE = new CmdlineEvent.Hide(1, false);

    @Test
    void aLineNobodyExpectedIsTheUsers() {
        assertFalse(watcher.isOwn(show(":", ""), now));
        assertFalse(watcher.isOwn(new CmdlineEvent.Pos(0, 1), now));
        assertFalse(watcher.isOwn(HIDE, now));
    }

    /** Every event of the expected line is the plug-in's, up to and including its hide. */
    @Test
    void thePluginsOwnLineIsOwnUntilItCloses() {
        watcher.expectOwn(":", now);
        assertTrue(watcher.isOwn(show(":", "ls"), now));
        assertTrue(watcher.isOwn(new CmdlineEvent.Pos(2, 1), now));
        assertTrue(watcher.isOwn(HIDE, now));
        assertFalse(watcher.isOwn(show(":", ""), now), "the expectation was used up");
    }

    @Test
    void ownLinesAreMatchedInOrderByType() {
        watcher.expectOwn("/", now);
        watcher.expectOwn(":", now);
        assertTrue(watcher.isOwn(show("/", "foo"), now));
        assertTrue(watcher.isOwn(HIDE, now));
        assertTrue(watcher.isOwn(show(":", "echo 1"), now));
        assertTrue(watcher.isOwn(HIDE, now));
    }

    @Test
    void aLineOfAnotherTypeIsNotTheExpectedOne() {
        watcher.expectOwn(":", now);
        assertFalse(watcher.isOwn(show("/", "x"), now));
    }

    /** Keys that did not open a line must not hide the user's next line for long. */
    @Test
    void anUnfulfilledExpectationExpires() {
        watcher.expectOwn(":", now);
        assertFalse(watcher.isOwn(show(":", ""), now + CmdlineWatcher.OWN_LINE_TIMEOUT_MS + 1));
    }

    /** A line inside the plug-in's own ({@code <C-r>=}) belongs to it too. */
    @Test
    void innerLinesFollowTheTopLevelLine() {
        watcher.expectOwn(":", now);
        assertTrue(watcher.isOwn(show(":", "x"), now));
        assertTrue(watcher.isOwn(new CmdlineEvent.Show("=", "1", 1, "", 0, 2), now));
        assertTrue(watcher.isOwn(new CmdlineEvent.Hide(2, false), now));
        assertTrue(watcher.isOwn(HIDE, now));
    }
}
