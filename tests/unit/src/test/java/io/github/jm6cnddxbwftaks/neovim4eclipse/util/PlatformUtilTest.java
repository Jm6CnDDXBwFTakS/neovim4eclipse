// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlatformUtilTest {

    @Test
    void windowsVariants() {
        assertTrue(PlatformUtil.isWindows("Windows 11"));
        assertTrue(PlatformUtil.isWindows("Windows Server 2022"));
        assertEquals("windows", PlatformUtil.osFamily("Windows 10"));
    }

    @Test
    void darwinIsNotWindows() {
        // Regression: the old contains("win") check matched "Darwin".
        assertFalse(PlatformUtil.isWindows("Darwin"));
        assertTrue(PlatformUtil.isMac("Darwin"));
    }

    @Test
    void macVariants() {
        assertTrue(PlatformUtil.isMac("Mac OS X"));
        assertTrue(PlatformUtil.isMac("macOS"));
        assertEquals("macos", PlatformUtil.osFamily("Mac OS X"));
    }

    @Test
    void linuxAndUnixLike() {
        assertTrue(PlatformUtil.isLinux("Linux"));
        assertTrue(PlatformUtil.isLinux("FreeBSD"));
        assertTrue(PlatformUtil.isLinux("SunOS"));
        assertEquals("linux", PlatformUtil.osFamily("Linux"));
    }

    @Test
    void unknownAndNullAreHandled() {
        assertEquals("unknown", PlatformUtil.osFamily("Plan 9"));
        assertEquals("unknown", PlatformUtil.osFamily(null));
        assertFalse(PlatformUtil.isWindows((String) null));
    }

    @Test
    void exactlyOneFamilyMatchesTheRealOs() {
        int matches = (PlatformUtil.isWindows() ? 1 : 0) + (PlatformUtil.isMac() ? 1 : 0)
                + (PlatformUtil.isLinux() ? 1 : 0);
        assertTrue(matches <= 1, "OS families must be mutually exclusive");
    }
}
