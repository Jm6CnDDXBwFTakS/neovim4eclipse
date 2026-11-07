// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    /**
     * A PAX record's length counts UTF-8 bytes: "17 path=a/ü.txt\n" is 17 bytes, 16 characters. A
     * record after a non-ASCII one is still found where its length says.
     */
    @Test
    void paxLengthsCountBytesNotCharacters() {
        String records = TestArchives.Tar.paxRecord("mtime", "1700000000") + TestArchives.Tar.paxRecord("path", "a/ü.txt")
                + TestArchives.Tar.paxRecord("comment", "née");
        assertTrue(records.startsWith("20 mtime=1700000000\n17 path=a/ü.txt\n"), records);
        assertEquals("a/ü.txt", ArchiveExtractor.paxPath(records.getBytes(StandardCharsets.UTF_8)));
        String pathLast = TestArchives.Tar.paxRecord("comment", "née") + TestArchives.Tar.paxRecord("path", "a/b");
        assertEquals("a/b", ArchiveExtractor.paxPath(pathLast.getBytes(StandardCharsets.UTF_8)),
                "the path after a non-ASCII record");
    }

    /** A file whose PAX name holds non-ASCII letters is unpacked under that name. */
    @Test
    void aNonAsciiPaxNameIsUnpacked() throws IOException {
        String name = "nvim/share/nvim/runtime/doc/" + "überlänge-".repeat(12) + "ñ.txt";
        untar(new TestArchives.Tar().folder("nvim/").paxFile(name, "x", TestArchives.Tar.paxRecord("mtime", "1700000000"))
                .gzipTo(tmp.resolve("u.tar.gz")));
        assertEquals("x", Files.readString(target().resolve(name.substring("nvim/".length()))));
    }

    /**
     * A GNU-format header (magic "ustar  \0") keeps its access and change times where ustar has the
     * name prefix; they are not read as one, so the file keeps its own name and top folder.
     */
    @Test
    void aGnuHeadersTimesAreNotANamePrefix() throws IOException {
        untar(new TestArchives.Tar().folder("nvim/").gnuFile("nvim/bin/nvim", "gnu").gzipTo(tmp.resolve("g.tar.gz")));
        assertEquals("gnu", Files.readString(target().resolve("bin/nvim")));
    }

    /** A POSIX ustar header's prefix is still joined to its name. */
    @Test
    void aUstarPrefixIsJoinedToTheName() throws IOException {
        untar(new TestArchives.Tar().folder("nvim/").prefixedFile("nvim/share", "a.txt", "p")
                .gzipTo(tmp.resolve("pre.tar.gz")));
        assertEquals("p", Files.readString(target().resolve("share/a.txt")));
    }
}
