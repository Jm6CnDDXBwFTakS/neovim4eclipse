// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

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
    void neovimModeShowsThePartsChosen() {
        assertEquals(EnumSet.of(StatusItem.CMD, StatusItem.STATUSLINE),
                StatusItem.shown(EnumSet.of(StatusItem.CMD, StatusItem.STATUSLINE), false));
        assertEquals(EnumSet.noneOf(StatusItem.class), StatusItem.shown(EnumSet.noneOf(StatusItem.class), false),
                "nothing chosen, nothing shown");
    }

    /** The indicator says where keys go, so it shows even with the mode part turned off. */
    @Test
    void eclipseModeShowsTheModeAloneWhateverWasChosen() {
        assertEquals(EnumSet.of(StatusItem.MODE), StatusItem.shown(EnumSet.of(StatusItem.STATUSLINE), true));
        assertEquals(EnumSet.of(StatusItem.MODE), StatusItem.shown(EnumSet.noneOf(StatusItem.class), true));
        assertEquals(EnumSet.of(StatusItem.MODE), StatusItem.shown(EnumSet.allOf(StatusItem.class), true));
    }

    @Test
    void theSetShownIsTheCallersOwn() {
        EnumSet<StatusItem> chosen = EnumSet.of(StatusItem.CMD);
        StatusItem.shown(chosen, false).add(StatusItem.MODE);
        assertEquals(EnumSet.of(StatusItem.CMD), chosen, "the chosen set is not changed through the result");
    }

    @Test
    void thereAreExactlyThreeItems() {
        assertEquals(3, StatusItem.values().length);
    }
}
