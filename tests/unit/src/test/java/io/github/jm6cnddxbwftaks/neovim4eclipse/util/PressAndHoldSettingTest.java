// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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
        List<List<String>> asked = new ArrayList<>();
        assertFalse(PressAndHoldSetting.probe(command -> {
            asked.add(command);
            return "0";
        }, null));
        assertEquals(List.of(List.of("/usr/bin/defaults", "read", "-g", "ApplePressAndHoldEnabled")), asked);
    }

    /** Turned off for Eclipse alone ({@code defaults write epp.package.java ...}): off, as macOS has it. */
    @Test
    void eclipsesOwnSettingComesFirst() {
        List<List<String>> asked = new ArrayList<>();
        assertFalse(PressAndHoldSetting.probe(command -> {
            asked.add(command);
            return command.contains("-g") ? "1" : "0";
        }, "epp.package.java"));
        assertEquals(List.of(List.of("/usr/bin/defaults", "read", "epp.package.java", "ApplePressAndHoldEnabled")),
                asked);
    }

    /** Eclipse has no setting of its own: the system-wide one counts. */
    @Test
    void withoutItsOwnSettingTheSystemWideOneCounts() {
        List<List<String>> asked = new ArrayList<>();
        assertFalse(PressAndHoldSetting.probe(command -> {
            asked.add(command);
            return command.contains("-g") ? "0" : null;
        }, "epp.package.java"));
        assertEquals(2, asked.size());
    }

    /** An id that is no application id (an option, a space) is not passed to defaults. */
    @Test
    void anOddApplicationIdIsNotAsked() {
        for (String id : new String[] { "-g", "", "a b" }) {
            List<List<String>> asked = new ArrayList<>();
            PressAndHoldSetting.probe(command -> {
                asked.add(command);
                return null;
            }, id);
            assertEquals(List.of(List.of("/usr/bin/defaults", "read", "-g", "ApplePressAndHoldEnabled")), asked, id);
        }
    }
}
