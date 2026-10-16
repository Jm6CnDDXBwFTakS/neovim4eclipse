// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

class StatusLineFormatTest {

    private static final EnumSet<StatusItem> ALL = EnumSet.allOf(StatusItem.class);

    @Test
    void allPartsAreJoinedInDisplayOrder() {
        assertEquals("-- NORMAL --  2d  E486: Pattern not found: x  [No Name] 1,1",
                StatusLineFormat.compose(ALL, "-- NORMAL --", "2d", "E486: Pattern not found: x", "[No Name] 1,1"));
    }

    @Test
    void onlyEnabledItemsAreShown() {
        assertEquals("-- INSERT --", StatusLineFormat.compose(EnumSet.of(StatusItem.MODE), "-- INSERT --", "2d", "msg", "sl"));
        assertEquals("2d  sl", StatusLineFormat.compose(EnumSet.of(StatusItem.CMD, StatusItem.STATUSLINE),
                "-- INSERT --", "2d", "msg", "sl"));
    }

    @Test
    void nothingEnabledGivesAnEmptyString() {
        assertEquals("", StatusLineFormat.compose(EnumSet.noneOf(StatusItem.class), "-- NORMAL --", "2d", "m", "s"));
    }

    @Test
    void emptyPartsLeaveNoStraySeparators() {
        assertEquals("-- NORMAL --  sl", StatusLineFormat.compose(ALL, "-- NORMAL --", "", null, "sl"));
        assertEquals("-- NORMAL --", StatusLineFormat.compose(ALL, "-- NORMAL --", "", "", ""));
        assertEquals("msg", StatusLineFormat.compose(ALL, "", "", "msg", ""));
    }

    @Test
    void whitespaceRunsAndLineBreaksBecomeOneSpace() {
        assertEquals("a b c", StatusLineFormat.cleanText("  a   b\n\tc  "));
        assertEquals("[No Name] [+] 1,1 All", StatusLineFormat.cleanText("[No Name] [+]                    1,1           All"));
        assertEquals("line one line two", StatusLineFormat.cleanText("line one\nline two"));
    }

    @Test
    void cleanTextHandlesNullAndBlank() {
        assertEquals("", StatusLineFormat.cleanText(null));
        assertEquals("", StatusLineFormat.cleanText("   \n "));
    }

    @Test
    void composeCleansFreeText() {
        assertEquals("-- NORMAL --  E1: a b", StatusLineFormat.compose(ALL, "-- NORMAL --", "", "E1:\na   b", ""));
    }
}
