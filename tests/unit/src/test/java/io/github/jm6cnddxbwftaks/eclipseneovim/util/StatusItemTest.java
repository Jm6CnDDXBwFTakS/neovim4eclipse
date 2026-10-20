// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

class StatusItemTest {

    @Test
    void keysAreStable() {
        assertEquals("mode", StatusItem.MODE.key());
        assertEquals("cmd", StatusItem.CMD.key());
        assertEquals("statusline", StatusItem.STATUSLINE.key());
    }

    @Test
    void defaultsShowEverythingExceptTheLongStatusline() {
        assertEquals(EnumSet.of(StatusItem.MODE, StatusItem.CMD), StatusItem.defaults());
    }

    @Test
    void thereAreExactlyThreeItems() {
        assertEquals(3, StatusItem.values().length);
    }
}
