// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link EditPath}: the file an {@code :e} or {@code :wq} name means when it reaches Eclipse as
 * typed. Normally Neovim has already made it absolute (host_commands_spec.lua); the rule is
 * Neovim's either way: relative to the window's folder, {@code ./} and {@code ../} included.
 */
class EditPathTest {

    private static final Path HOME = Path.of("/home/user").toAbsolutePath();
    /** The window's folder: the project root, where the plug-in's {@code :lcd} puts it. */
    private static final Path PROJECT = Path.of("/ws/app").toAbsolutePath();

    /** An editor reads its whole file: a device, or a link to one, may have no end. */
    @Test
    void onlyARegularFileCanBeOpened(@TempDir Path folder) throws IOException {
        Path file = Files.writeString(folder.resolve("a.txt"), "text");
        assertNull(EditPath.whyNotOpened(file, "a.txt"));
        assertEquals("\"d\" is a directory", EditPath.whyNotOpened(folder, "d"));
        Path device = Path.of("/dev/null");
        assumeTrue(Files.exists(device), "a system with /dev/null");
        assertEquals("\"n\" is not a file", EditPath.whyNotOpened(device, "n"));
        Path link = Files.createSymbolicLink(folder.resolve("notes.txt"), device);
        assertEquals("\"notes.txt\" is not a file", EditPath.whyNotOpened(link, "notes.txt"));
        assertFalse(EditPath.mayNeverEnd(folder.resolve("missing")), "a missing file has its own rule");
        assertFalse(EditPath.mayNeverEnd(link, new FilePaths.Platform(true, true, UnaryOperator.identity())),
                "never on Windows, where ordinary files can look like this");
    }

    /** {@code /proc/self/pagemap} says it is a regular empty file and has no end: judged by where it is. */
    @Test
    void aKernelFileIsRefusedThoughItLooksRegular(@TempDir Path folder) throws IOException {
        Path kernel = Path.of("/proc/self/pagemap");
        assumeTrue(Files.isRegularFile(kernel), "a system with /proc");
        assertEquals("\"p\" is not a file", EditPath.whyNotOpened(kernel, "p"));
        Path link = Files.createSymbolicLink(folder.resolve("notes.txt"), kernel);
        assertEquals("\"notes.txt\" is not a file", EditPath.whyNotOpened(link, "notes.txt"));
    }

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

    /** As Neovim's messages show a name ({@code :~:.}): {@code "new.txt" [New]}, not the full path. */
    @Test
    void aNameIsShownRelativeToTheWindowsFolderOrHome() {
        assertEquals("new.txt", EditPath.shown(PROJECT.resolve("new.txt"), HOME, PROJECT));
        assertEquals(Path.of("src", "A.java").toString(), EditPath.shown(PROJECT.resolve("src/A.java"), HOME, PROJECT));
        assertEquals("~" + java.io.File.separator + "notes.txt", EditPath.shown(HOME.resolve("notes.txt"), HOME, PROJECT));
        Path outside = Path.of("/etc/hosts").toAbsolutePath();
        assertEquals(outside.toString(), EditPath.shown(outside, HOME, PROJECT));
        assertEquals(PROJECT.resolve("x").toString(), EditPath.shown(PROJECT.resolve("x"), HOME, null), "no folder: in full");
    }

    @Test
    void aFolderInOtherCaseStillShortensTheNameWhereNamesIgnoreCase() {
        // macOS's rule (FilePaths): /ws/App and /ws/app are one folder; the part shown keeps its case.
        // The separators are this machine's, since the Paths are: on a Windows host /ws/app is
        // \ws\app, which only a rule that knows \ reads as a path (found 2026-10-29).
        boolean windows = FilePaths.Platform.current().windows();
        FilePaths.Platform ignoresCase = new FilePaths.Platform(windows, true, path -> path);
        assertEquals(Path.of("src/A.java").toString(),
                EditPath.shown(Path.of("/ws/app/src/A.java"), Path.of("/home/u"), Path.of("/ws/App"), ignoresCase));
        FilePaths.Platform keepsCase = new FilePaths.Platform(windows, false, path -> path);
        assertEquals(Path.of("/ws/app/src/A.java").toString(),
                EditPath.shown(Path.of("/ws/app/src/A.java"), Path.of("/home/u"), Path.of("/ws/App"), keepsCase),
                "case kept (Linux)");
    }
}
