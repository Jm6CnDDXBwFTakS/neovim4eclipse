// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
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
     * Neovim's data folder stays in the real data home, where the plug-in finds its own Neovim, so
     * the name's slash puts it inside the plug-in's folder: {@code ~/.local/share/neovim4eclipse-plugin/tests},
     * not a folder of its own beside it. On Windows Neovim keeps its data in {@code %LOCALAPPDATA%},
     * which the plug-in does not use, so it is not checked there.
     */
    @Test
    void theTestNeovimsDataFolderIsInsideThePluginsFolder() {
        Assumptions.assumeFalse(PlatformUtil.isWindows(), "Neovim's data home on Windows is %LOCALAPPDATA%");
        String data = (String) SessionProbe.lua(session, "return vim.fn.stdpath('data')");
        assertTrue(FilePaths.same(Path.of(data), PluginFolders.pluginFolder().resolve("tests")),
                "the data folder is <plug-in folder>/tests: " + data);
    }
}
