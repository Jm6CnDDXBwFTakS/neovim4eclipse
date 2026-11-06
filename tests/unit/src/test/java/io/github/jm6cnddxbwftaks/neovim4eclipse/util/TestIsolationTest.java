// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * The tests of this module run in folders of their own, under {@code target/} (the root
 * {@code pom.xml}'s surefire configuration): a test that reads or writes the developer's real
 * folders passes on one machine and fails on another, a CI runner above all, which has none of
 * them. This guard fails at once if the isolation is lost, say by a module that overrides the
 * surefire configuration, instead of leaving that to show up as a test that fails only somewhere.
 *
 * <p>Example: with the isolation, {@link PluginFolders#dataHome()} is
 * {@code tests/unit/target/neovim-home/data}; without it, {@code ~/.local/share}.
 */
class TestIsolationTest {

    /** {@code target/} of this module, as the build passes it. */
    private static Path root() {
        String root = System.getProperty("test.isolated.root");
        assertTrue(root != null && !root.isBlank() && !root.startsWith("${"),
                "test.isolated.root is not set: the root pom.xml's surefire configuration was replaced");
        return Path.of(root).toAbsolutePath().normalize();
    }

    @Test
    void thePluginsDataFolderIsUnderTarget() {
        Path data = PluginFolders.dataHome().toAbsolutePath().normalize();
        assertTrue(data.startsWith(root()), "the data home " + data + " is outside " + root()
                + ": XDG_DATA_HOME is not set for the tests, so they see the developer's own folder");
    }

    @Test
    void neovimsOwnFoldersAreUnderTarget() {
        for (String variable : new String[] { "XDG_CONFIG_HOME", "XDG_STATE_HOME", "XDG_CACHE_HOME", "XDG_DATA_HOME" }) {
            String value = System.getenv(variable);
            assertTrue(value != null && Path.of(value).toAbsolutePath().normalize().startsWith(root()),
                    variable + " is " + value + ", not a folder under " + root());
        }
        assertTrue("neovim4eclipse-plugin/tests".equals(System.getenv("NVIM_APPNAME")),
                "NVIM_APPNAME is not set: a test Neovim could read the developer's config");
    }
}
