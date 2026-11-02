// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link NeovimInstaller} with a tiny Neovim-like archive whose hash the test pins in a release of its
 * own. Neovim is never run: {@code versionOf} stands in for {@code nvim --version}.
 */
class NeovimInstallerTest {

    private static final NeovimPlatform LA = NeovimPlatform.byCode("la").orElseThrow();
    private static final NeovimVersion VERSION = new NeovimVersion(0, 12, 6);
    private static final Function<Path, NeovimVersion> RUNS = p -> VERSION;

    @TempDir
    Path tmp;

    Path folder;
    Path archive;
    NeovimRelease release;

    @BeforeEach
    void pinATestArchive() throws IOException {
        folder = tmp.resolve("neovim");
        archive = TestArchives.neovimTarGz(tmp.resolve("nvim-linux-arm64.tar.gz"), "nvim-linux-arm64");
        Path macArchive = TestArchives.neovimTarGz(tmp.resolve("nvim-macos-arm64.tar.gz"), "nvim-macos-arm64x");
        release = release(sha(archive), Files.size(archive), sha(macArchive), Files.size(macArchive));
    }

    private static String sha(Path file) {
        return NeovimInstaller.sha256(file, InstallProgress.NONE);
    }

    private static NeovimRelease release(String laSha, long laSize, String maSha, long maSize) throws IOException {
        return NeovimRelease.parse(new StringReader("""
                version=0.12.6
                url=https://github.com/neovim/neovim/releases/download/v{version}/{asset}
                la.asset=nvim-linux-arm64.tar.gz
                la.size=%d
                la.sha256=%s
                ma.asset=nvim-macos-arm64.tar.gz
                ma.size=%d
                ma.sha256=%s
                """.formatted(laSize, laSha, maSize, maSha)));
    }

    private NeovimInstaller.Result install(Path file, boolean inUse, Function<Path, NeovimVersion> versionOf) {
        return NeovimInstaller.install(file, release, LA, folder, inUse, versionOf, InstallProgress.NONE);
    }

    @Test
    void thePinnedArchiveIsInstalledIntoItsCodeFolder() throws IOException {
        byte[] before = Files.readAllBytes(archive);
        NeovimInstaller.Result r = install(archive, false, RUNS);
        assertEquals(NeovimInstaller.Outcome.INSTALLED, r.outcome());
        assertEquals(folder.resolve("la/bin/nvim"), r.program());
        assertEquals(sha(archive), r.sha256());
        assertTrue(PluginNeovim.isInstall(folder.resolve("la"), false));
        assertFalse(Files.exists(folder.resolve("la.new")));
        assertFalse(Files.exists(folder.resolve("la.old")));
        assertArrayEquals(before, Files.readAllBytes(archive), "the chosen file is only read");
    }

    @Test
    void aSecondInstallReplacesTheFirst() throws IOException {
        install(archive, false, RUNS);
        Files.writeString(folder.resolve("la/marker"), "old");
        install(archive, false, RUNS);
        assertFalse(Files.exists(folder.resolve("la/marker")));
        assertTrue(PluginNeovim.isInstall(folder.resolve("la"), false));
        assertFalse(Files.exists(folder.resolve("la.old")));
    }

    @Test
    void whileNeovimRunsTheNewOneWaitsForTheRestart() throws IOException {
        install(archive, false, RUNS);
        Files.writeString(folder.resolve("la/marker"), "running");
        NeovimInstaller.Result r = install(archive, true, RUNS);
        assertEquals(NeovimInstaller.Outcome.WAITS_FOR_RESTART, r.outcome());
        assertTrue(Files.exists(folder.resolve("la/marker")), "the running one is untouched");
        assertTrue(PluginNeovim.isInstall(folder.resolve("la.new"), false));

        NeovimInstaller.finishPending(folder);
        assertFalse(Files.exists(folder.resolve("la/marker")));
        assertFalse(Files.exists(folder.resolve("la.new")));
        assertTrue(PluginNeovim.isInstall(folder.resolve("la"), false));
    }

    @Test
    void anotherArchiveIsRefusedWithBothHashes() throws IOException {
        Path other = TestArchives.zip(tmp.resolve("other.tar.gz"), "x/y", "z");
        NeovimInstallException e = assertThrows(NeovimInstallException.class, () -> install(other, false, RUNS));
        assertEquals(NeovimInstallException.Reason.WRONG_ARCHIVE, e.reason());
        assertTrue(e.getMessage().contains(release.forPlatform(LA).orElseThrow().sha256()), e.getMessage());
        assertTrue(e.getMessage().contains(sha(other)), e.getMessage());
        assertEquals(new NeovimInstallException.Found("other.tar.gz", Files.size(other), sha(other)),
                e.found().orElseThrow(), "for the failure dialog's fields");
        assertFalse(Files.exists(folder.resolve("la")));
    }

    @Test
    void anotherPlatformsArchiveNamesTheRightOne() {
        Path mac = tmp.resolve("nvim-macos-arm64.tar.gz");
        NeovimInstallException e = assertThrows(NeovimInstallException.class, () -> install(mac, false, RUNS));
        assertEquals(NeovimInstallException.Reason.WRONG_PLATFORM, e.reason());
        assertEquals("This is the archive for macOS arm64 (nvim-macos-arm64.tar.gz); this Eclipse needs "
                + "nvim-linux-arm64.tar.gz, for Linux arm64.", e.getMessage());
    }

    @Test
    void aNeovimThatDoesNotRunIsNotInstalled() throws IOException {
        install(archive, false, RUNS);
        Files.writeString(folder.resolve("la/marker"), "kept");
        NeovimInstallException e = assertThrows(NeovimInstallException.class, () -> install(archive, false, p -> null));
        assertEquals(NeovimInstallException.Reason.DOES_NOT_RUN, e.reason());
        assertTrue(Files.exists(folder.resolve("la/marker")), "the installed Neovim stays");
        assertFalse(Files.exists(folder.resolve("la.new")), "the unpacked one is removed");

        e = assertThrows(NeovimInstallException.class, () -> install(archive, false, p -> new NeovimVersion(0, 11, 0)));
        assertTrue(e.getMessage().contains("0.11.0"), e.getMessage());
    }

    @Test
    void cancellingLeavesNothingBehind() {
        InstallProgress cancelled = new InstallProgress() {
            @Override
            public void step(String name, long total) {
            }

            @Override
            public void worked(long amount) {
            }

            @Override
            public boolean cancelled() {
                return true;
            }
        };
        NeovimInstallException e = assertThrows(NeovimInstallException.class,
                () -> NeovimInstaller.install(archive, release, LA, folder, false, RUNS, cancelled));
        assertEquals(NeovimInstallException.Reason.CANCELLED, e.reason());
        assertFalse(Files.exists(folder.resolve("la")));
        assertFalse(Files.exists(folder.resolve("la.new")));
    }

    @Test
    void anIncompleteWaitingFolderIsNotSwappedIn() throws IOException {
        install(archive, false, RUNS);
        Files.createDirectories(folder.resolve("la.new/bin"));
        NeovimInstaller.finishPending(folder);
        assertTrue(PluginNeovim.isInstall(folder.resolve("la"), false));
        assertTrue(Files.exists(folder.resolve("la.new")), "left for the next install to replace");
    }
}
