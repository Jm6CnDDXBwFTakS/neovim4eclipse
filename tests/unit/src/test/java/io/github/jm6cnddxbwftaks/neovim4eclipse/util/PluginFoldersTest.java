// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Where the plug-in keeps its files (see {@link PluginFolders}). */
class PluginFoldersTest {

    /** An absolute path on every OS: the temp folder, which Path.of accepts as it is. */
    private static final Path SOMEWHERE = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
    private static final String USER_HOME = SOMEWHERE.resolve("user-home").toString();

    private static Path dataHome(Map<String, String> env) {
        return PluginFolders.dataHome(env, USER_HOME);
    }

    @Test
    void xdgDataHomeComesFirst() {
        Path xdg = SOMEWHERE.resolve("xdg-data");
        assertEquals(xdg, dataHome(Map.of("XDG_DATA_HOME", xdg.toString(), "HOME", SOMEWHERE.resolve("h").toString())));
    }

    @Test
    void thenHomeDotLocalShare() {
        Path home = SOMEWHERE.resolve("home");
        assertEquals(home.resolve(".local").resolve("share"), dataHome(Map.of("HOME", home.toString())));
    }

    @Test
    void thenJavasHomeFolder() {
        assertEquals(Path.of(USER_HOME, ".local", "share"), dataHome(Map.of()));
    }

    @Test
    void aRelativeXdgDataHomeIsIgnored() {
        // The XDG specification: "If an implementation encounters a relative path ... it should consider the path invalid and ignore it."
        Path home = SOMEWHERE.resolve("home");
        assertEquals(home.resolve(".local").resolve("share"),
                dataHome(Map.of("XDG_DATA_HOME", "relative/data", "HOME", home.toString())));
    }

    @Test
    void blankOrRelativeHomeIsIgnoredToo() {
        assertEquals(Path.of(USER_HOME, ".local", "share"), dataHome(Map.of("XDG_DATA_HOME", "  ", "HOME", "home")));
    }

    @Test
    void thePluginsFoldersAreInTheDataHome() {
        assertEquals(PluginFolders.dataHome().resolve("neovim4eclipse-plugin"), PluginFolders.pluginFolder());
        assertEquals(PluginFolders.pluginFolder().resolve("neovim"), PluginFolders.neovimFolder());
    }
}
