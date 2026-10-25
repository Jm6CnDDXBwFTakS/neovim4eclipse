// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NvimExecutableTest {

    /** Resolve with an empty PATH and no fallback folders, so the result does not depend on this machine. */
    private static String resolveNowhere(String configured, boolean windows) {
        return NvimExecutable.resolve(configured, "", windows, List.of());
    }

    /** Git Bash's SHELL would give Neovim on Windows bash with cmd.exe's flags; see prepareEnvironment. */
    @Test
    void shellIsDroppedOnWindowsOnly() {
        Map<String, String> windows = new HashMap<>(Map.of("SHELL", "/usr/bin/bash", "PATH", "C:\\bin"));
        NvimExecutable.prepareEnvironment(windows, true);
        assertEquals(Map.of("PATH", "C:\\bin"), windows);

        Map<String, String> linux = new HashMap<>(Map.of("SHELL", "/bin/zsh", "PATH", "/usr/bin"));
        NvimExecutable.prepareEnvironment(linux, false);
        assertEquals(Map.of("SHELL", "/bin/zsh", "PATH", "/usr/bin"), linux);
    }

    @Test
    void blankOrNullUsesPlatformDefault() {
        assertEquals("nvim", resolveNowhere(null, false));
        assertEquals("nvim", resolveNowhere("", false));
        assertEquals("nvim.exe", resolveNowhere("   ", true));
    }

    @Test
    void commandNamesAreKeptAndTrimmed() {
        assertEquals("nvim", resolveNowhere("nvim", false));
        assertEquals("nvim.exe", resolveNowhere("nvim.exe", true));
        assertEquals("nvim", resolveNowhere("  nvim  ", false));
    }

    @Test
    void nameOnPathIsKeptEvenIfAlsoInAFallbackFolder() throws IOException {
        Path onPath = pathDirectory("onpath");
        Path fallback = Files.createTempDirectory("fallback");
        try {
            Files.createFile(onPath.resolve("nvim"));
            Files.createFile(fallback.resolve("nvim"));
            assertEquals("nvim", NvimExecutable.resolve("nvim", onPath.toString(), false, List.of(fallback)));
        } finally {
            deleteTree(onPath);
            deleteTree(fallback);
        }
    }

    @Test
    void nameMissingFromPathIsFoundInTheFirstFallbackFolderThatHasIt() throws IOException {
        Path empty = pathDirectory("empty");
        Path homebrew = Files.createTempDirectory("homebrew");
        Path other = Files.createTempDirectory("other");
        try {
            Path exe = Files.createFile(homebrew.resolve("nvim"));
            Files.createFile(other.resolve("nvim"));
            assertEquals(exe.toAbsolutePath().toString(),
                    NvimExecutable.resolve("nvim", empty.toString(), false, List.of(empty, homebrew, other)));
        } finally {
            deleteTree(empty);
            deleteTree(homebrew);
            deleteTree(other);
        }
    }

    @Test
    void fallbackFoldersOnWindowsTryExecutableSuffixes() throws IOException {
        Path dir = Files.createTempDirectory("neovimbin");
        try {
            Path exe = Files.createFile(dir.resolve("nvim.exe"));
            assertEquals(exe.toAbsolutePath().toString(), NvimExecutable.resolve("nvim", "", true, List.of(dir)));
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void relativePathsAreNotSearched() throws IOException {
        Path dir = Files.createTempDirectory("fallback");
        try {
            Files.createDirectories(dir.resolve("bin"));
            Files.createFile(dir.resolve("bin").resolve("nvim"));
            assertEquals("bin/nvim", NvimExecutable.resolve("bin/nvim", "", false, List.of(dir)));
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void searchFoldersAreAbsolute() {
        for (Path dir : NvimExecutable.searchFolders()) {
            assertTrue(dir.isAbsolute(), dir.toString());
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Test
    void existingAbsolutePathIsReturnedAsAbsolute() throws IOException {
        Path file = Files.createTempFile("fake-nvim", "");
        try {
            assertEquals(file.toAbsolutePath().toString(), NvimExecutable.resolve(file.toString()));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void missingAbsolutePathIsLeftForTheOsToReport() {
        String missing = Path.of(System.getProperty("java.io.tmpdir"), "definitely-not-here-nvim").toString();
        assertEquals(missing, NvimExecutable.resolve(missing));
    }

    @Test
    void relativeNameIsNeverResolvedAgainstTheWorkingDirectory() throws IOException {
        // A file named like the command in the CWD must not hijack PATH lookup.
        Path inCwd = Path.of("hijack-candidate-nvim");
        Files.writeString(inCwd, "");
        try {
            assertEquals("hijack-candidate-nvim", resolveNowhere("hijack-candidate-nvim", false));
        } finally {
            Files.deleteIfExists(inCwd);
        }
    }

    @Test
    void embedCommandAddsEmbedFlagAndGuardThenExtraArgs() {
        String abs = Path.of(System.getProperty("java.io.tmpdir"), "no-such-nvim").toAbsolutePath().toString();
        assertEquals(List.of(abs, "--embed", "--cmd", "let g:neovim4eclipse=1", "--clean"),
                NvimExecutable.embedCommand(abs, List.of("--clean")));
    }

    @Test
    void embedCommandSkipsNullAndBlankExtraArgs() {
        String abs = Path.of(System.getProperty("java.io.tmpdir"), "no-such-nvim").toAbsolutePath().toString();
        assertEquals(List.of(abs, "--embed", "--cmd", "let g:neovim4eclipse=1"),
                NvimExecutable.embedCommand(abs, null));
        assertEquals(List.of(abs, "--embed", "--cmd", "let g:neovim4eclipse=1", "-u"),
                NvimExecutable.embedCommand(abs, java.util.Arrays.asList(null, " ", "-u")));
    }

    @Test
    void defaultCommandNameMatchesPlatform() {
        assertEquals(PlatformUtil.isWindows() ? "nvim.exe" : "nvim", NvimExecutable.defaultCommandName());
    }

    // ---- PATH search ----

    /**
     * A new folder to put in a test's PATH: relative ({@code target/fakepath123}), not in the system
     * temp folder. These tests pass {@code windows=false} to check the macOS/Linux rules on every OS,
     * and those rules split PATH at {@code :}. An absolute Windows path has a {@code :} after its
     * drive letter ({@code C:\Users\...}), so it would be cut in two and never found; a relative
     * one has none. The search resolves it against the working directory (the module folder, where
     * Maven runs the tests), the same way it does a relative PATH entry.
     */
    private static Path pathDirectory(String prefix) throws IOException {
        Path target = Path.of("target");
        Files.createDirectories(target);
        return Files.createTempDirectory(target, prefix);
    }

    @Test
    void isAvailableFindsCommandInPathDirectory() throws IOException {
        Path dir = pathDirectory("fakepath");
        Path exe = Files.createFile(dir.resolve("nvim"));
        try {
            assertTrue(NvimExecutable.isAvailable("nvim", dir.toString(), false));
            assertFalse(NvimExecutable.isAvailable("other", dir.toString(), false));
        } finally {
            Files.deleteIfExists(exe);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void isAvailableSkipsBlankPathEntriesAndHandlesMissingPath() {
        assertFalse(NvimExecutable.isAvailable("nvim", null, false));
        assertFalse(NvimExecutable.isAvailable("nvim", "  ", false));
        assertFalse(NvimExecutable.isAvailable("nvim", "::", false));
    }

    @Test
    void isAvailableOnWindowsTriesExecutableSuffixes() throws IOException {
        Path dir = pathDirectory("fakepathwin");
        Path exe = Files.createFile(dir.resolve("nvim.exe"));
        try {
            assertTrue(NvimExecutable.isAvailable("nvim", dir.toString(), true));
            assertFalse(NvimExecutable.isAvailable("nvim", dir.toString(), false),
                    "no suffix probing off Windows");
        } finally {
            Files.deleteIfExists(exe);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void isAvailableChecksAbsolutePathsDirectly() throws IOException {
        Path file = Files.createTempFile("abs-nvim", "");
        try {
            assertTrue(NvimExecutable.isAvailable(file.toAbsolutePath().toString(), "", false));
            Files.delete(file);
            assertFalse(NvimExecutable.isAvailable(file.toAbsolutePath().toString(), "", false));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // ---- folders (the preference may name Neovim's install folder) ----

    @TempDir
    Path tmp;

    /** Creates {@code relative} under {@link #tmp} as an empty file, with its parent folders. */
    private Path file(String relative) throws IOException {
        Path path = tmp.resolve(relative);
        Files.createDirectories(path.getParent());
        return Files.createFile(path);
    }

    @Test
    void aFolderFindsItsBinNvim() throws IOException {
        Path exe = file("neovim/bin/nvim");
        assertEquals(exe.toAbsolutePath().toString(), resolveNowhere(tmp.resolve("neovim").toString(), false));
    }

    @Test
    void aBinFolderItselfWorksToo() throws IOException {
        Path exe = file("neovim/bin/nvim");
        assertEquals(exe.toAbsolutePath().toString(), resolveNowhere(tmp.resolve("neovim/bin").toString(), false));
    }

    @Test
    void anArchiveExtractedAsItIsIsFoundInsideTheFolder() throws IOException {
        // tar -xzf nvim-linux-x86_64.tar.gz -C ~/.local/opt/neovim keeps the archive's top folder.
        Path exe = file("neovim/nvim-linux-x86_64/bin/nvim");
        assertEquals(exe.toAbsolutePath().toString(), resolveNowhere(tmp.resolve("neovim").toString(), false));
    }

    @Test
    void ofSeveralArchivesTheNewestNeovimWins() throws IOException {
        // Versions side by side, renamed so they can coexist; the names are not trusted, the programs are asked.
        Path older = file("neovim/nvim-0.13.0/bin/nvim"); // name says newer, program is older
        Path newer = file("neovim/nvim-0.12.6/bin/nvim");
        Map<Path, NeovimVersion> versions = Map.of(older.toAbsolutePath(), new NeovimVersion(0, 12, 6),
                newer.toAbsolutePath(), new NeovimVersion(0, 13, 1));
        assertEquals(newer.toAbsolutePath(),
                NvimExecutable.findInFolder(tmp.resolve("neovim"), "nvim", false, versions::get));
    }

    @Test
    void archivesThatDoNotRunAsNeovimAreSkipped() throws IOException {
        // An archive for another processor cannot run here: its version is unknown (null).
        file("neovim/nvim-linux-arm64/bin/nvim");
        Path runs = file("neovim/nvim-linux-x86_64/bin/nvim");
        Map<Path, NeovimVersion> versions = Map.of(runs.toAbsolutePath(), new NeovimVersion(0, 12, 6));
        assertEquals(runs.toAbsolutePath(),
                NvimExecutable.findInFolder(tmp.resolve("neovim"), "nvim", false, versions::get));
    }

    @Test
    void whenNoArchiveRunsNoneIsChosen() throws IOException {
        file("neovim/nvim-a/bin/nvim");
        file("neovim/nvim-b/bin/nvim");
        assertNull(NvimExecutable.findInFolder(tmp.resolve("neovim"), "nvim", false, p -> null));
    }

    @Test
    void aSingleArchiveIsTakenWithoutRunningIt() throws IOException {
        Path only = file("neovim/nvim-linux-x86_64/bin/nvim");
        assertEquals(only.toAbsolutePath(), NvimExecutable.findInFolder(tmp.resolve("neovim"), "nvim", false, p -> {
            throw new AssertionError("ran " + p);
        }));
    }

    @Test
    void binNvimWinsOverAnArchiveFolder() throws IOException {
        Path exe = file("neovim/bin/nvim");
        file("neovim/nvim-linux-x86_64/bin/nvim");
        assertEquals(exe.toAbsolutePath(), NvimExecutable.findInFolder(tmp.resolve("neovim"), "nvim", false, p -> {
            throw new AssertionError("bin/nvim is a deliberate choice, no version needed: ran " + p);
        }));
    }

    @Test
    void aFolderOnWindowsFindsNvimExe() throws IOException {
        Path exe = file("Neovim/bin/nvim.exe");
        assertEquals(exe.toAbsolutePath().toString(), resolveNowhere(tmp.resolve("Neovim").toString(), true));
    }

    @Test
    void aFolderWithoutNeovimNamesTheExpectedProgram() throws IOException {
        Files.createDirectories(tmp.resolve("empty"));
        assertEquals(tmp.resolve("empty").resolve("bin").resolve("nvim").toString(),
                resolveNowhere(tmp.resolve("empty").toString(), false));
    }

    @Test
    void searchFoldersAreInstallFoldersToo() throws IOException {
        Path exe = file("homebrew/bin/nvim");
        assertEquals(exe.toAbsolutePath().toString(),
                NvimExecutable.resolve("nvim", "", false, List.of(tmp.resolve("missing"), tmp.resolve("homebrew"))));
    }

    // ---- blank preference: the dedicated folder first ----

    @Test
    void blankUsesTheDedicatedFolderBeforePath() throws IOException {
        Path dedicated = file("opt/neovim/bin/nvim");
        Path onPath = file("path/nvim");
        assertEquals(dedicated.toAbsolutePath().toString(), NvimExecutable.resolve("", onPath.getParent().toString(),
                false, tmp.resolve("opt/neovim"), List.of()));
    }

    @Test
    void blankFallsBackToPathWhenTheDedicatedFolderIsEmpty() throws IOException {
        Path onPath = file("path/nvim");
        assertEquals("nvim", NvimExecutable.resolve(null, onPath.getParent().toString(), false,
                tmp.resolve("opt/neovim"), List.of()));
    }

    @Test
    void blankThenSearchesTheUsualFolders() throws IOException {
        Path exe = file("homebrew/bin/nvim");
        assertEquals(exe.toAbsolutePath().toString(), NvimExecutable.resolve("  ", "", false,
                tmp.resolve("opt/neovim"), List.of(tmp.resolve("homebrew"))));
    }

    @Test
    void anExplicitNameDoesNotUseTheDedicatedFolder() throws IOException {
        file("opt/neovim/bin/nvim");
        assertEquals("nvim", NvimExecutable.resolve("nvim", "", false, tmp.resolve("opt/neovim"), List.of()));
    }

    @Test
    void dedicatedFolderIsUnderTheHomeFolder() {
        assertEquals(Path.of(System.getProperty("user.home"), ".local", "opt", "neovim"),
                NvimExecutable.dedicatedFolder());
    }

    @Test
    void aLeadingTildeIsTheHomeFolder() {
        String home = System.getProperty("user.home");
        assertEquals(home + "/.local/opt/neovim", NvimExecutable.expandHome("~/.local/opt/neovim"));
        assertEquals(home, NvimExecutable.expandHome("~"));
        assertEquals("~user/x", NvimExecutable.expandHome("~user/x"), "only the current user's ~");
        assertEquals("/opt/~", NvimExecutable.expandHome("/opt/~"));
    }

    @Test
    void locateFindsTheFileBehindACommandName() throws IOException {
        Path exe = file("path/nvim");
        assertEquals(exe.toAbsolutePath(), NvimExecutable.locate("nvim", exe.getParent().toString(), false));
        assertEquals(exe, NvimExecutable.locate(exe.toString(), "", false));
        assertNull(NvimExecutable.locate("nvim", "", false));
        assertNull(NvimExecutable.locate(tmp.resolve("missing").toString(), "", false));
    }
}
