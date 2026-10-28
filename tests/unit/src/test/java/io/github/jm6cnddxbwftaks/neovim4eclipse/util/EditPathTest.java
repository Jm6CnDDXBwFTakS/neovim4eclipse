// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * {@link EditPath}: the file an {@code :e} or {@code :wq} name means when it reaches Eclipse as
 * typed. Normally Neovim has already made it absolute (host_commands_spec.lua); the rule is
 * Neovim's either way: relative to the window's folder, {@code ./} and {@code ../} included.
 */
class EditPathTest {

    private static final Path HOME = Path.of("/home/user").toAbsolutePath();
    /** The window's folder: the project root, where the plug-in's {@code :lcd} puts it. */
    private static final Path PROJECT = Path.of("/ws/app").toAbsolutePath();

    @Test
    void aRelativeNameIsInTheWindowsFolder() {
        assertEquals(PROJECT.resolve("Util.java"), EditPath.resolve("Util.java", HOME, PROJECT));
        assertEquals(PROJECT.resolve("src/Util.java"), EditPath.resolve("src/Util.java", HOME, PROJECT));
    }

    /** As in Neovim, {@code ./x} is the same file as {@code x} (until 0.1.9 it meant the file's folder). */
    @Test
    void dotSlashAndDotDotAreTheWindowsFolderToo() {
        assertEquals(PROJECT.resolve("Util.java"), EditPath.resolve("./Util.java", HOME, PROJECT));
        assertEquals(PROJECT.resolveSibling("x.txt"), EditPath.resolve("../x.txt", HOME, PROJECT),
                "not limited to the project");
    }

    @Test
    void homeAndAbsolutePathsAreUsedAsTheyAre() {
        assertEquals(HOME.resolve(".config/nvim/init.lua"), EditPath.resolve("~/.config/nvim/init.lua", HOME, PROJECT));
        assertEquals(HOME, EditPath.resolve("~", HOME, PROJECT));
        Path absolute = Path.of("/etc/hosts").toAbsolutePath();
        assertEquals(absolute, EditPath.resolve(absolute.toString(), HOME, PROJECT));
        assertEquals(absolute, EditPath.resolve(absolute.toString(), HOME, null), "no folder needed");
    }

    @Test
    void nothingForABlankNameOrARelativeOneWithoutAFolder() {
        assertNull(EditPath.resolve("a.txt", HOME, null), "a document without a file, outside any project");
        assertNull(EditPath.resolve("  ", HOME, PROJECT));
        assertNull(EditPath.resolve(null, HOME, PROJECT));
    }
}
