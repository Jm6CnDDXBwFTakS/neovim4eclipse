// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

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
    void fallbackFoldersAreAbsolute() {
        for (Path dir : NvimExecutable.fallbackDirectories()) {
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
}
