// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * A variable's name by the operating system's rule (see {@link EnvVariables}). Both rules are
 * tested on every OS: which one applies is a parameter.
 */
class EnvVariablesTest {

    @Test
    void windowsFindsAVariableUnderAnySpelling() {
        assertEquals("C:\\data", EnvVariables.get(Map.of("Xdg_Data_Home", "C:\\data"), "XDG_DATA_HOME", true));
        assertEquals("C:\\data", EnvVariables.get(Map.of("xdg_data_home", "C:\\data"), "XDG_DATA_HOME", true));
        assertEquals("C:\\data", EnvVariables.get(Map.of("XDG_DATA_HOME", "C:\\data"), "XDG_DATA_HOME", true));
    }

    @Test
    void theExactSpellingComesFirst() {
        // Windows itself never holds both; a map built by hand may.
        Map<String, String> both = new LinkedHashMap<>();
        both.put("home", "other");
        both.put("HOME", "exact");
        assertEquals("exact", EnvVariables.get(both, "HOME", true));
    }

    @Test
    void linuxAndMacOsTellTheSpellingsApart() {
        assertNull(EnvVariables.get(Map.of("Xdg_Data_Home", "/data"), "XDG_DATA_HOME", false), "another variable there");
        assertEquals("/data", EnvVariables.get(Map.of("XDG_DATA_HOME", "/data"), "XDG_DATA_HOME", false));
    }

    @Test
    void aVariableThatIsNotSetIsNull() {
        assertNull(EnvVariables.get(Map.of("PATH", "C:\\bin"), "HOME", true));
        assertNull(EnvVariables.get(Map.of("PATH", "/bin"), "HOME", false));
    }

    @Test
    void windowsRemovesEverySpelling() {
        Map<String, String> env = new HashMap<>(Map.of("Shell", "/usr/bin/bash", "PATH", "C:\\bin"));
        EnvVariables.remove(env, "SHELL", true);
        assertEquals(Map.of("PATH", "C:\\bin"), env);
    }

    @Test
    void linuxAndMacOsRemoveOnlyTheNamedOne() {
        Map<String, String> env = new HashMap<>(Map.of("Shell", "a", "SHELL", "/bin/zsh"));
        EnvVariables.remove(env, "SHELL", false);
        assertEquals(Map.of("Shell", "a"), env);
    }
}
