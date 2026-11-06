// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FilePaths;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PlatformUtil;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PluginFolders;

/**
 * The UI tests' Neovim reads no personal config: not the {@code init.lua} or {@code init.vim} of
 * whoever runs the build, nor their plug-ins or saved registers. The build sets
 * {@code NVIM_APPNAME} and the {@code XDG_*} folders for the test Eclipse (see this module's
 * {@code pom.xml}), and Neovim inherits them from it. Without that, a personal setting could make a
 * test pass on one machine and fail on another.
 */
class NeovimHomeEndToEndTest extends EndToEndTestBase {

    @Test
    void theTestNeovimReadsNoPersonalConfig() {
        assertEquals("neovim4eclipse-plugin/tests", SessionProbe.lua(session, "return vim.env.NVIM_APPNAME"));
        String config = (String) SessionProbe.lua(session, "return vim.fn.stdpath('config')");
        assertTrue(Path.of(config).normalize().toString().contains(Path.of("target", "neovim-home").toString()),
                "the config folder is under target/: " + config);
        assertEquals("", SessionProbe.lua(session, "return vim.env.MYVIMRC or ''"), "no init.lua or init.vim ran");
    }

    /**
     * Neovim's data folder is inside the plug-in's folder, both under the tests' own data home
     * ({@code target/neovim-home/data}, XDG_DATA_HOME in this module's {@code pom.xml}), which the
     * plug-in shares with Neovim ({@link PluginFolders}): the name's slash puts it there,
     * {@code target/neovim-home/data/neovim4eclipse-plugin/tests}. On Windows Neovim adds
     * {@code -data} to the name ({@code ...\neovim4eclipse-plugin\tests-data}). Nothing of a test
     * run lands in the developer's own data folder.
     */
    @Test
    void theTestNeovimsDataFolderIsInsideThePluginsFolder() {
        String data = (String) SessionProbe.lua(session, "return vim.fn.stdpath('data')");
        String expected = PlatformUtil.isWindows() ? "tests-data" : "tests";
        assertTrue(FilePaths.same(Path.of(data), PluginFolders.pluginFolder().resolve(expected)),
                "the data folder is <plug-in folder>/" + expected + ": " + data);
        assertTrue(Path.of(data).normalize().toString().contains(Path.of("target", "neovim-home", "data").toString()),
                "the data folder is under target/: " + data);
    }
}
