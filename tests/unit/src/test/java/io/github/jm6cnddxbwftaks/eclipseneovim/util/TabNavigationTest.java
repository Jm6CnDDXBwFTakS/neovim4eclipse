// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static io.github.jm6cnddxbwftaks.eclipseneovim.util.TabNavigation.Action.FIRST;
import static io.github.jm6cnddxbwftaks.eclipseneovim.util.TabNavigation.Action.LAST;
import static io.github.jm6cnddxbwftaks.eclipseneovim.util.TabNavigation.Action.NEXT;
import static io.github.jm6cnddxbwftaks.eclipseneovim.util.TabNavigation.Action.PREVIOUS;
import static io.github.jm6cnddxbwftaks.eclipseneovim.util.TabNavigation.target;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TabNavigationTest {

    @Test
    void nextWrapsAround() {
        assertEquals(1, target(NEXT, 0, 4, 0));
        assertEquals(0, target(NEXT, 3, 4, 0));
    }

    @Test
    void nextWithACountGoesToThatTab() {
        assertEquals(2, target(NEXT, 0, 4, 3)); // 3gt: the third tab
        assertEquals(0, target(NEXT, 3, 4, 1));
        assertEquals(1, target(NEXT, 1, 4, 9)); // no tab 9: stay
    }

    @Test
    void previousWrapsAndCountsSteps() {
        assertEquals(3, target(PREVIOUS, 0, 4, 0));
        assertEquals(1, target(PREVIOUS, 3, 4, 2)); // 2gT
        assertEquals(2, target(PREVIOUS, 1, 4, 3)); // wraps
        assertEquals(1, target(PREVIOUS, 1, 4, 4)); // a full circle
    }

    @Test
    void firstAndLast() {
        assertEquals(0, target(FIRST, 2, 4, 0));
        assertEquals(3, target(LAST, 0, 4, 0));
    }

    @Test
    void unknownCurrentTabAndNoTabs() {
        assertEquals(0, target(NEXT, -1, 3, 0));
        assertEquals(2, target(PREVIOUS, -1, 3, 0));
        assertEquals(-1, target(NEXT, -1, 0, 0));
    }
}
