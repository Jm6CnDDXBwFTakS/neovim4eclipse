// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link InstalledVersions} against the real Neovim the tests run with ({@code -Dnvim.path=...}):
 * its {@code --version} is read, and CI's Neovim is the supported minimum or newer.
 */
@Tag("integration")
class InstalledVersionsIntegrationTest {

    private static final String NVIM = System.getProperty("nvim.path", "nvim");

    @Test
    void readsTheVersionOfTheTestNeovim() {
        Path program = NvimExecutable.locate(NvimExecutable.resolve(NVIM));
        assumeTrue(program != null, "nvim not available");
        NeovimVersion version = InstalledVersions.of(program);
        assertNotNull(version, "no version from " + program + " --version");
        assertTrue(version.isSupported(), "the tests' Neovim " + version + " is older than " + NeovimVersion.MINIMUM);
        assertSame(version, InstalledVersions.of(program), "the second answer comes from the cache");
    }

    @Test
    void aFileThatIsNotAProgramHasNoVersion(@TempDir Path tmp) throws IOException {
        Path text = Files.writeString(tmp.resolve("nvim"), "not a program");
        assertNull(InstalledVersions.of(text));
    }
}
