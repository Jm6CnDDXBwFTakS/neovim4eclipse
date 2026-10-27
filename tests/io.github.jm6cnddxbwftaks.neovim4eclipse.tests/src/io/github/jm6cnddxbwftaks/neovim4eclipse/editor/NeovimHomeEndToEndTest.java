// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;

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
        assertEquals("neovim4eclipse-tests", SessionProbe.lua(session, "return vim.env.NVIM_APPNAME"));
        String config = (String) SessionProbe.lua(session, "return vim.fn.stdpath('config')");
        assertTrue(Path.of(config).normalize().toString().contains(Path.of("target", "neovim-home").toString()),
                "the config folder is under target/: " + config);
        assertEquals("", SessionProbe.lua(session, "return vim.env.MYVIMRC or ''"), "no init.lua or init.vim ran");
    }
}
