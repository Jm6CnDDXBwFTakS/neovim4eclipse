// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Reading macOS's press-and-hold setting from what {@code defaults} prints. */
class PressAndHoldSettingTest {

    @Test
    void neverWrittenIsOn() {
        assertTrue(PressAndHoldSetting.parse(null), "defaults fails: macOS's default");
    }

    @Test
    void turnedOffIsOff() {
        assertFalse(PressAndHoldSetting.parse("0\n"));
        assertFalse(PressAndHoldSetting.parse("false"));
        assertFalse(PressAndHoldSetting.parse("NO"));
    }

    @Test
    void turnedOnIsOn() {
        assertTrue(PressAndHoldSetting.parse("1\n"));
        assertTrue(PressAndHoldSetting.parse("true"));
    }

    @Test
    void asksDefaultsByItsFullPathForTheSystemWideSetting() {
        List<List<String>> asked = new java.util.ArrayList<>();
        assertFalse(PressAndHoldSetting.probe(command -> {
            asked.add(command);
            return "0";
        }));
        assertEquals(List.of(List.of("/usr/bin/defaults", "read", "-g", "ApplePressAndHoldEnabled")), asked);
    }
}
