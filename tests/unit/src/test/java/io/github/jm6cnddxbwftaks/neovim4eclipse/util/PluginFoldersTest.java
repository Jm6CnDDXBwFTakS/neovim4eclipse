// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Where the plug-in keeps its files (see {@link PluginFolders}). Both rules are tested on every OS:
 * which one applies is a parameter, and the paths are absolute on the OS running the test.
 */
class PluginFoldersTest {

    /** An absolute path on every OS: the temp folder, which Path.of accepts as it is. */
    private static final Path SOMEWHERE = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
    private static final String USER_HOME = SOMEWHERE.resolve("user-home").toString();
    private static final Path XDG = SOMEWHERE.resolve("xdg-data");
    private static final Path HOME = SOMEWHERE.resolve("home");
    private static final Path LOCAL_APP_DATA = SOMEWHERE.resolve("local-app-data");

    private static Path unix(Map<String, String> env) {
        return PluginFolders.dataHome(env, USER_HOME, false);
    }

    private static Path windows(Map<String, String> env) {
        return PluginFolders.dataHome(env, USER_HOME, true);
    }

    // ---- Linux and macOS: the XDG rule ----

    @Test
    void unixXdgDataHomeComesFirst() {
        assertEquals(XDG, unix(Map.of("XDG_DATA_HOME", XDG.toString(), "HOME", HOME.toString())));
    }

    @Test
    void unixThenHomeDotLocalShare() {
        assertEquals(HOME.resolve(".local").resolve("share"), unix(Map.of("HOME", HOME.toString())));
    }

    @Test
    void unixThenJavasHomeFolder() {
        assertEquals(Path.of(USER_HOME, ".local", "share"), unix(Map.of()));
    }

    @Test
    void unixIgnoresLocalAppData() {
        assertEquals(HOME.resolve(".local").resolve("share"),
                unix(Map.of("LOCALAPPDATA", LOCAL_APP_DATA.toString(), "HOME", HOME.toString())));
    }

    @Test
    void unixIgnoresARelativeXdgDataHome() {
        // The XDG specification: "If an implementation encounters a relative path ... it should consider the path invalid and ignore it."
        assertEquals(HOME.resolve(".local").resolve("share"),
                unix(Map.of("XDG_DATA_HOME", "relative/data", "HOME", HOME.toString())));
    }

    @Test
    void unixIgnoresABlankOrRelativeHomeToo() {
        assertEquals(Path.of(USER_HOME, ".local", "share"), unix(Map.of("XDG_DATA_HOME", "  ", "HOME", "home")));
    }

    // ---- Windows: Neovim's rule, one test per row of the table in PluginFolders' class comment ----

    /** {@code XDG_DATA_HOME=C:\xdg-test}: Neovim uses {@code C:\xdg-test\nvim-data}. */
    @Test
    void windowsXdgDataHomeComesFirst() {
        assertEquals(XDG, windows(Map.of("XDG_DATA_HOME", XDG.toString(), "LOCALAPPDATA", LOCAL_APP_DATA.toString(),
                "HOME", HOME.toString())));
    }

    /** {@code LOCALAPPDATA} set, whatever {@code HOME} says: Neovim uses {@code %LOCALAPPDATA%\nvim-data}. */
    @Test
    void windowsThenLocalAppDataWhateverHomeSays() {
        assertEquals(LOCAL_APP_DATA, windows(Map.of("LOCALAPPDATA", LOCAL_APP_DATA.toString(), "HOME", HOME.toString())));
    }

    /** {@code LOCALAPPDATA=C:\fake-local}: {@code C:\fake-local\nvim-data}; the variable is read, not assumed. */
    @Test
    void windowsReadsLocalAppDataWhereverItPoints() {
        Path elsewhere = SOMEWHERE.resolve("fake-local");
        assertEquals(elsewhere, windows(Map.of("LOCALAPPDATA", elsewhere.toString())));
    }

    /** No {@code LOCALAPPDATA}, {@code HOME=C:\fake-home}: {@code C:\fake-home\AppData\Local\nvim-data}. */
    @Test
    void windowsThenHomeAppDataLocal() {
        assertEquals(HOME.resolve("AppData").resolve("Local"), windows(Map.of("HOME", HOME.toString())));
    }

    /** Neither: the user's profile folder, {@code C:\Users\you\AppData\Local\nvim-data}. */
    @Test
    void windowsThenJavasHomeFolder() {
        assertEquals(Path.of(USER_HOME, "AppData", "Local"), windows(Map.of()));
    }

    // The table's last row (NVIM_APPNAME=neovim4eclipse-plugin/tests) is about Neovim, not this
    // rule: NeovimHomeEndToEndTest checks it against the real Neovim.

    @Test
    void windowsIgnoresRelativeOrBlankVariables() {
        assertEquals(Path.of(USER_HOME, "AppData", "Local"),
                windows(Map.of("XDG_DATA_HOME", "relative", "LOCALAPPDATA", "  ", "HOME", "home")));
    }

    /**
     * Windows ignores the case of a variable's name, and Neovim reads {@code Xdg_Data_Home} and
     * {@code localappdata} as it reads the usual spellings: the plug-in's folder must not end up
     * elsewhere than Neovim's.
     */
    @Test
    void windowsReadsTheVariablesUnderAnySpelling() {
        assertEquals(XDG, windows(Map.of("Xdg_Data_Home", XDG.toString(), "LOCALAPPDATA", LOCAL_APP_DATA.toString())));
        assertEquals(LOCAL_APP_DATA, windows(Map.of("LocalAppData", LOCAL_APP_DATA.toString(), "Home", HOME.toString())));
        assertEquals(HOME.resolve("AppData").resolve("Local"), windows(Map.of("Home", HOME.toString())));
    }

    /** On Linux and macOS {@code Xdg_Data_Home} is another variable, which Neovim does not read. */
    @Test
    void unixReadsOnlyTheExactNames() {
        assertEquals(Path.of(USER_HOME, ".local", "share"), unix(Map.of("Xdg_Data_Home", XDG.toString(), "Home", HOME.toString())));
    }

    @Test
    void thePluginsFoldersAreInTheDataHome() {
        assertEquals(PluginFolders.dataHome().resolve("neovim4eclipse-plugin"), PluginFolders.pluginFolder());
        assertEquals(PluginFolders.pluginFolder().resolve("neovim"), PluginFolders.neovimFolder());
    }
}
