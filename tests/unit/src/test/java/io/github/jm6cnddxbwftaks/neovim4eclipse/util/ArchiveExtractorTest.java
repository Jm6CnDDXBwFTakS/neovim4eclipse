// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ArchiveExtractor} on archives the test writes byte by byte ({@link TestArchives}). */
class ArchiveExtractorTest {

    @TempDir
    Path tmp;

    private Path target() {
        return tmp.resolve("out");
    }

    private void untar(Path archive) throws IOException {
        ArchiveExtractor.extract(archive, NeovimRelease.Kind.TAR_GZ, target(), InstallProgress.NONE);
    }

    @Test
    void aReleaseTarLosesItsTopFolder() throws IOException {
        untar(TestArchives.neovimTarGz(tmp.resolve("a.tar.gz"), "nvim-linux-arm64"));
        assertEquals("#!/bin/sh\n", Files.readString(target().resolve("bin/nvim")));
        assertTrue(Files.isDirectory(target().resolve("share/nvim/runtime")));
        assertFalse(Files.exists(target().resolve("nvim-linux-arm64")));
    }

    @Test
    void theExecuteBitIsKept() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX file modes");
        untar(TestArchives.neovimTarGz(tmp.resolve("a.tar.gz"), "nvim"));
        assertTrue(Files.getPosixFilePermissions(target().resolve("bin/nvim")).contains(PosixFilePermission.OWNER_EXECUTE));
        assertFalse(Files.getPosixFilePermissions(target().resolve("share/nvim/runtime/filetype.lua"))
                .contains(PosixFilePermission.OWNER_EXECUTE));
    }

    @Test
    void aPaxNameReplacesTheShortOne() throws IOException {
        String longName = "nvim/share/nvim/runtime/" + "very-long-folder-name/".repeat(8) + "file.lua";
        untar(new TestArchives.Tar().folder("nvim/").paxFile(longName, "x").gzipTo(tmp.resolve("p.tar.gz")));
        assertEquals("x", Files.readString(target().resolve(longName.substring("nvim/".length()))));
        assertFalse(Files.exists(target().resolve("ignored-short-name")));
    }

    /** A PAX header after a GNU long name does not take that name: the file still gets it. */
    @Test
    void aGnuLongNameSurvivesAPaxHeaderAfterIt() throws IOException {
        String longName = "nvim/share/nvim/runtime/" + "very-long-folder-name/".repeat(8) + "file.lua";
        untar(new TestArchives.Tar().folder("nvim/").gnuLongNameThenPaxFile(longName, "x")
                .gzipTo(tmp.resolve("lx.tar.gz")));
        assertEquals("x", Files.readString(target().resolve(longName.substring("nvim/".length()))));
        assertFalse(Files.exists(target().resolve("ignored-short-name")));
    }

    /** A link stops the unpacking: skipped, it would leave an install that lacks a file yet looks whole. */
    @Test
    void aLinkIsRefused() throws IOException {
        Path archive = new TestArchives.Tar().folder("nvim/").symlink("nvim/evil", "/etc/passwd")
                .file("nvim/ok", "ok", false).gzipTo(tmp.resolve("l.tar.gz"));
        NeovimInstallException e = assertThrows(NeovimInstallException.class, () -> untar(archive));
        assertEquals(NeovimInstallException.Reason.WRONG_ARCHIVE, e.reason());
        assertTrue(e.getMessage().contains("a link: nvim/evil"), e.getMessage());
        assertFalse(Files.exists(target().resolve("evil"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void aNameLeavingTheTargetIsRefused() throws IOException {
        Path archive = new TestArchives.Tar().folder("nvim/").file("nvim/../../escaped", "x", false)
                .gzipTo(tmp.resolve("z.tar.gz"));
        NeovimInstallException e = assertThrows(NeovimInstallException.class, () -> untar(archive));
        assertEquals(NeovimInstallException.Reason.WRONG_ARCHIVE, e.reason());
        assertFalse(Files.exists(tmp.resolve("escaped")));
    }

    @Test
    void anAbsoluteNameIsRefused() throws IOException {
        Path archive = new TestArchives.Tar().file("/tmp/abs", "x", false).gzipTo(tmp.resolve("a.tar.gz"));
        assertThrows(NeovimInstallException.class, () -> untar(archive));
    }

    @Test
    void twoTopFoldersAreNotARelease() throws IOException {
        Path archive = new TestArchives.Tar().file("a/x", "x", false).file("b/y", "y", false)
                .gzipTo(tmp.resolve("t.tar.gz"));
        assertThrows(NeovimInstallException.class, () -> untar(archive));
    }

    @Test
    void aWindowsZipLosesItsTopFolder() throws IOException {
        Path zip = TestArchives.zip(tmp.resolve("n.zip"), "nvim-win64/bin/nvim.exe", "MZ",
                "nvim-win64/share/nvim/runtime/filetype.lua", "-- ft");
        ArchiveExtractor.extract(zip, NeovimRelease.Kind.ZIP, target(), InstallProgress.NONE);
        assertEquals("MZ", Files.readString(target().resolve("bin/nvim.exe")));
        assertTrue(PluginNeovim.isInstall(target(), true));
    }

    @Test
    void aZipSlipEntryIsRefused() throws IOException {
        Path zip = TestArchives.zip(tmp.resolve("s.zip"), "nvim-win64/../../evil.txt", "x");
        assertThrows(NeovimInstallException.class,
                () -> ArchiveExtractor.extract(zip, NeovimRelease.Kind.ZIP, target(), InstallProgress.NONE));
        assertFalse(Files.exists(tmp.resolve("evil.txt")));
    }

    @Test
    void paxRecordsAreParsed() {
        assertEquals("a/b", ArchiveExtractor.paxPath("12 path=a/b\n20 mtime=1700000000\n".getBytes()));
        assertEquals(null, ArchiveExtractor.paxPath("20 mtime=1700000000\n".getBytes()));
    }
}
