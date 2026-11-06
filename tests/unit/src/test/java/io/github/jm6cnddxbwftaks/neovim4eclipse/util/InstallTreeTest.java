// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The tree hash of an install ({@link InstallTree}): one hash for every file, as sha256sum lists them. */
class InstallTreeTest {

    /** SHA-256 of "a\n" and of "b\n". */
    private static final String A = "87428fc522803d31065e7bce3cf03fe475096631e5e07bbd7a0fde60c4cf25c7";
    private static final String B = "0263829989b6fd954f72baaf2fc64bc2e2f01d692d4de72986ea808f6e99813f";

    @TempDir
    Path tmp;

    private Path home() throws IOException {
        Path home = tmp.resolve("la");
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin/nvim"), "a\n");
        Files.createDirectories(home.resolve("share/nvim/runtime"));
        Files.writeString(home.resolve("share/nvim/runtime/filetype.lua"), "b\n");
        return home;
    }

    /**
     * The value {@code sha256sum} gives for the same two lines, computed in a shell:
     * {@code printf '%s  bin/nvim\n%s  share/nvim/runtime/filetype.lua\n' $A $B | sha256sum}. The
     * update tool (releng/UpdateNeovimDownloads.java, which also installs CI's Neovim) uses that same rule, so this pins it.
     */
    @Test
    void theHashIsSha256sumsOutputHashed() throws IOException {
        String expected = "c4d825ff98b713563fdc9c8ace8d5f77896de98f8a85f76894ec84543203efb7";
        assertEquals(expected, InstallTree.of(Map.of("bin/nvim", A, "share/nvim/runtime/filetype.lua", B)));
        assertEquals(Optional.of(expected), InstallTree.of(home()));
    }

    @Test
    void anyChangeChangesIt() throws IOException {
        Path home = home();
        String before = InstallTree.of(home).orElseThrow();
        Files.writeString(home.resolve("share/nvim/runtime/filetype.lua"), "c\n");
        assertNotEquals(before, InstallTree.of(home).orElseThrow(), "a changed file");
        Files.writeString(home.resolve("share/nvim/runtime/filetype.lua"), "b\n");
        assertEquals(before, InstallTree.of(home).orElseThrow());
        Files.writeString(home.resolve("bin/lua51.dll"), "x");
        assertNotEquals(before, InstallTree.of(home).orElseThrow(), "an added file, such as a planted DLL");
    }

    /**
     * Files named like Finder's and Explorer's are deleted, any case, at any depth, so the hash is
     * the release's again; until then the install counts as none. Not skipped: Neovim would load
     * {@code ._x.lua} for a {@code require("._x")}, unchecked.
     */
    @Test
    void filesTheSystemAddsAreDeletedNotSkipped() throws IOException {
        Path home = home();
        String before = InstallTree.of(home).orElseThrow();
        List<Path> added = List.of(home.resolve(".DS_Store"), home.resolve("share/THUMBS.DB"),
                home.resolve("share/nvim/runtime/Desktop.ini"), home.resolve("share/nvim/runtime/._x.lua"));
        for (Path file : added) {
            Files.writeString(file, "added");
        }
        assertEquals(Optional.empty(), InstallTree.of(home), "counted as no install while they are there");
        assertTrue(InstallTree.removeSystemFiles(home));
        for (Path file : added) {
            assertFalse(Files.exists(file), file + " left");
        }
        assertEquals(before, InstallTree.of(home).orElseThrow());
        assertTrue(Files.exists(home.resolve("share/nvim/runtime/filetype.lua")), "other files kept");
    }

    /** A read-only one (Explorer marks desktop.ini so) is deleted too. */
    @Test
    void aReadOnlySystemFileIsDeletedToo() throws IOException {
        Path home = home();
        Path ini = Files.writeString(home.resolve("desktop.ini"), "[.ShellClassInfo]");
        if (!ini.toFile().setWritable(false)) {
            assumeTrue(false, "cannot make a file read-only here");
        }
        assertTrue(InstallTree.removeSystemFiles(home));
        assertFalse(Files.exists(ini));
    }

    @Test
    void aLinkMakesItNoInstall() throws IOException {
        Path home = home();
        try {
            Files.createSymbolicLink(home.resolve("bin/link"), home.resolve("bin/nvim"));
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "no symbolic links here: " + e);
        }
        assertEquals(Optional.empty(), InstallTree.of(home));
    }

    @Test
    void aMissingOrEmptyFolderHasNoHash() throws IOException {
        assertEquals(Optional.empty(), InstallTree.of(tmp.resolve("none")));
        assertEquals(Optional.empty(), InstallTree.of(Files.createDirectories(tmp.resolve("empty"))));
    }

    /** Paths sort by their UTF-8 bytes, as {@code LC_ALL=C sort} does. */
    @Test
    void pathsSortByTheirBytes() {
        assertTrue(InstallTree.compareUtf8("Z", "a") < 0);
        assertTrue(InstallTree.compareUtf8("a-b", "a/b") < 0, "'-' is 0x2d, '/' is 0x2f");
        assertTrue(InstallTree.compareUtf8("z", "é") < 0, "é is 0xc3 0xa9 in UTF-8");
        assertEquals(InstallTree.of(Map.of("b", A, "a", B)), InstallTree.of(Map.of("a", B, "b", A)),
                "the map's order does not matter");
    }
}
