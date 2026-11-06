// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PluginFolders;

/**
 * The test Eclipse uses a data folder of its own under {@code target/} (XDG_DATA_HOME in this
 * module's {@code pom.xml}), so the plug-in's folder the tests see is never the developer's: it
 * holds no dedicated Neovim unless a test put one there ({@link DedicatedNeovim}), as on a CI
 * runner, and the clean-up the plug-in does at each Neovim start touches nothing real. This guard
 * fails at once if that is lost.
 */
class TestIsolationUiTest {

    @Test
    void thePluginsDataFolderIsTheTestsOwn() {
        String root = System.getProperty("test.isolated.root");
        assertTrue(root != null && !root.isBlank() && !root.startsWith("${"), "test.isolated.root is not set");
        Path data = PluginFolders.dataHome().toAbsolutePath().normalize();
        assertTrue(data.startsWith(Path.of(root).toAbsolutePath().normalize()),
                "the data home " + data + " is outside " + root + ": XDG_DATA_HOME is not set for the test Eclipse");
    }
}
