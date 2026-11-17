// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The one rule of the second Neovim, as {@link ScreenSession} checks it once Neovim has started
 * ({@link ScreenSession#unsafeOptions}): every way to write a file is off. Without Neovim; the
 * real answer is {@code ScreenSessionIntegrationTest}'s.
 */
class ScreenSessionTest {

    /** The answer of {@code LuaScripts.SCREEN_SETUP} when the rule holds, as msgpack decodes it. */
    private static Map<String, Object> safe() {
        Map<String, Object> options = new HashMap<>();
        options.put("write", false);
        options.put("modeline", false);
        options.put("updatecount", 0L);
        options.put("undofile", false);
        options.put("shadafile", "NONE");
        options.put("readers", 0L);
        return options;
    }

    @Test
    void theRuleHoldsWhenEveryWayToWriteIsOff() {
        assertEquals(List.of(), ScreenSession.unsafeOptions(safe()));
    }

    @Test
    void eachOpenWayIsNamed() {
        Map<String, Object> options = safe();
        options.put("write", true);
        options.put("updatecount", 200L);
        options.put("shadafile", "");
        options.put("readers", 1L); // a reader left: gf on a shown name would reach it
        assertEquals(List.of("write", "updatecount", "shadafile", "readers"), ScreenSession.unsafeOptions(options));
    }

    @Test
    void aMissingOrStrangeAnswerCountsAsEveryWayOpen() {
        List<String> all = List.of("write", "modeline", "undofile", "updatecount", "shadafile", "readers");
        assertEquals(all, ScreenSession.unsafeOptions(null));
        assertEquals(all, ScreenSession.unsafeOptions("not a table"));
        assertEquals(all, ScreenSession.unsafeOptions(Map.of()));
        Map<String, Object> options = safe();
        options.put("write", "false"); // a string is no boolean false
        assertEquals(List.of("write"), ScreenSession.unsafeOptions(options));
    }

    @Test
    void theFixedArgumentsComeFirstAndCloseEveryWay() {
        assertTrue(ScreenSession.FIXED_ARGS.contains("--clean"), "no user config, no plug-ins, no shada");
        assertTrue(ScreenSession.FIXED_ARGS.contains("-n"), "no swap file");
        assertEquals("set nowrite nomodeline",
                ScreenSession.FIXED_ARGS.get(ScreenSession.FIXED_ARGS.indexOf("--cmd") + 1));
    }
}
