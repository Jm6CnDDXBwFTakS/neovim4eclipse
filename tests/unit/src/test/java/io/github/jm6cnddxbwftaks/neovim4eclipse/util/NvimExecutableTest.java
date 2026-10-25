// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NvimExecutableTest {

    /**
     * Stands in for {@code nvim --version}: every file the tests create counts as a working Neovim
     * 0.12.6. Tests about the version check pass their own.
     */
    private static final Function<Path, NeovimVersion> ANY_FILE_RUNS = p -> new NeovimVersion(0, 12, 6);

    /** Resolve with an empty PATH and no fallback folders, so the result does not depend on this machine. */
    private static String resolveNowhere(String configured, boolean windows) {
        return resolve(configured, "", windows, List.of());
    }

    private static String resolve(String configured, String pathEnv, boolean windows, List<Path> searchFolders) {
        return resolve(configured, pathEnv, windows, null, searchFolders);
    }

    private static String resolve(String configured, String pathEnv, boolean windows, Path dedicated,
            List<Path> searchFolders) {
        return NvimExecutable.find(configured, pathEnv, windows, dedicated, searchFolders, ANY_FILE_RUNS).command();
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
    void aCommandNameIsNeitherLookedUpNorSearchedFor() throws IOException {
        // Only a blank preference searches; a value names one Neovim, by an absolute path.
        Path onPath = pathDirectory("onpath");
        Path fallback = Files.createTempDirectory("fallback");
        try {
            Files.createFile(onPath.resolve("nvim"));
            Files.createFile(fallback.resolve("nvim"));
            assertEquals("nvim", resolve("nvim", onPath.toString(), false, List.of(fallback)));
            assertEquals("nvim", resolve("  nvim  ", onPath.toString(), false, List.of(fallback)));
            assertNull(NvimExecutable.locate("nvim"), "a command name is not looked up on PATH");
        } finally {
            deleteTree(onPath);
            deleteTree(fallback);
        }
    }

    @Test
    void blankFindsNvimOnPathAsAnAbsolutePath() throws IOException {
        Path onPath = pathDirectory("onpath");
        try {
            Path exe = Files.createFile(onPath.resolve("nvim"));
            assertEquals(exe.toAbsolutePath().toString(), resolve("", onPath.toString(), false, List.of()));
        } finally {
            deleteTree(onPath);
        }
    }

    @Test
    void blankMissingFromPathIsFoundInTheFirstUsualFolderThatHasIt() throws IOException {
        Path empty = pathDirectory("empty");
        Path homebrew = Files.createTempDirectory("homebrew");
        Path other = Files.createTempDirectory("other");
        try {
            Path exe = Files.createFile(homebrew.resolve("nvim"));
            Files.createFile(other.resolve("nvim"));
            assertEquals(exe.toAbsolutePath().toString(),
                    resolve("", empty.toString(), false, List.of(empty, homebrew, other)));
        } finally {
            deleteTree(empty);
            deleteTree(homebrew);
            deleteTree(other);
        }
    }

    @Test
    void blankOnWindowsFindsNvimExeInAUsualFolder() throws IOException {
        Path dir = Files.createTempDirectory("neovimbin");
        try {
            Path exe = Files.createFile(dir.resolve("nvim.exe"));
            assertEquals(exe.toAbsolutePath().toString(), resolve("", "", true, List.of(dir)));
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void startingRefusesAValueThatIsNotAnAbsolutePath() {
        for (String value : List.of("nvim", "nvim.exe", " bin/nvim ")) {
            NeovimNotFoundException e = assertThrows(NeovimNotFoundException.class,
                    () -> NvimExecutable.embedCommand(value, null), value);
            assertEquals("The Neovim folder or program must be an absolute path (a leading ~ is fine): "
                    + value.strip(), e.getMessage());
        }
    }

    @Test
    void aBlankPreferenceThatFindsNothingSaysSo() {
        assertEquals("No Neovim found in the default folders", NvimExecutable.notFound("").getMessage());
        assertEquals("No Neovim found in the default folders", NvimExecutable.notFound(null).getMessage());
    }

    @Test
    void relativePathsAreNotSearched() throws IOException {
        Path dir = Files.createTempDirectory("fallback");
        try {
            Files.createDirectories(dir.resolve("bin"));
            Files.createFile(dir.resolve("bin").resolve("nvim"));
            assertEquals("bin/nvim", resolve("bin/nvim", "", false, List.of(dir)));
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
     * drive letter ({@code C:\Users\...}), so it would be cut in two, {@code C} and
     * {@code \Users\...}, and the second found only when the build runs on drive C: (it passes on
     * most desktops, fails on CI runners that build on D:). A relative one has no colon. The search
     * resolves it against the working directory (the module folder, where Maven runs the tests),
     * the same way it does a relative PATH entry.
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

    // ---- folders (the preference may name a folder that holds Neovim) ----

    @TempDir
    Path tmp;

    /** Creates {@code relative} under {@link #tmp} as an empty file, with its parent folders. */
    private Path file(String relative) throws IOException {
        Path path = tmp.resolve(relative);
        Files.createDirectories(path.getParent());
        return Files.createFile(path);
    }

    /**
     * Creates an install as release archives and installers lay it out: {@code relative/bin/program}
     * with Neovim's runtime files in {@code relative/share/nvim/runtime}.
     */
    private Path install(String relative, String program) throws IOException {
        Files.createDirectories(tmp.resolve(relative).resolve("share/nvim/runtime"));
        return file(relative + "/bin/" + program);
    }

    /**
     * Searched installs come back under the folder's real path; compare with that (on macOS the temp
     * folder {@code /var/...} is really {@code /private/var/...}).
     */
    private static Path real(Path path) throws IOException {
        return path.toRealPath();
    }

    private NvimExecutable.Found findIn(String folder, Function<Path, NeovimVersion> versionOf) {
        return NvimExecutable.findInFolder(tmp.resolve(folder), "nvim", false, true, versionOf);
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
        // tar -xzf nvim-linux-x86_64.tar.gz -C ~/.local/share/neovim4eclipse-plugin/neovim keeps the archive's top folder.
        Path exe = install("neovim/nvim-linux-x86_64", "nvim");
        assertEquals(real(exe).toString(), resolveNowhere(tmp.resolve("neovim").toString(), false));
    }

    @Test
    void anArchiveExtractedByWindowsExplorerIsFound() throws IOException {
        // "Extract All" puts the zip's top folder inside one more, named after the zip.
        Path exe = install("neovim/nvim-win-arm64_0.12.6/nvim-win-arm64", "nvim.exe");
        assertEquals(real(exe).toString(), resolveNowhere(tmp.resolve("neovim").toString(), true));
    }

    @Test
    void theSearchGoesDownToItsDepthAndNoFurther() throws IOException {
        assertEquals(4, NvimExecutable.maxSearchDepth());
        Path deepest = install("neovim/a/b", "nvim"); // neovim/a/b/bin/nvim: depth 4
        install("neovim/a/b/c", "nvim"); // depth 5
        assertEquals(List.of(real(deepest)), NvimExecutable.installsIn(tmp.resolve("neovim"), "nvim", false));
    }

    @Test
    void aBinNvimWithoutNeovimsRuntimeIsNotAnInstall() throws IOException {
        // A stray copy, a wrapper script or a half-built source tree has no share/nvim/runtime beside it.
        file("neovim/tools/bin/nvim");
        assertEquals(List.of(), NvimExecutable.installsIn(tmp.resolve("neovim"), "nvim", false));
    }

    @Test
    void belowTheFolderWindowsTakesOnlyNvimExe() throws IOException {
        install("neovim/wrapper", "nvim.cmd");
        Path exe = install("neovim/nvim-win64", "nvim.exe");
        assertEquals(List.of(real(exe)), NvimExecutable.installsIn(tmp.resolve("neovim"), "nvim", true));
    }

    @Test
    void aLinkedInstallCountsOnce() throws IOException {
        Path exe = install("neovim/nvim-0.12.6", "nvim");
        try {
            Files.createSymbolicLink(tmp.resolve("neovim/nvim-current"), tmp.resolve("neovim/nvim-0.12.6"));
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "cannot create symbolic links here (on Windows: needs Developer Mode)");
        }
        assertEquals(List.of(real(exe)), NvimExecutable.installsIn(tmp.resolve("neovim"), "nvim", false));
    }

    /**
     * The Windows counterpart of {@link #aLinkedInstallCountsOnce}: a junction, which any user may
     * create (a symbolic link needs Developer Mode or an administrator), so this one runs everywhere
     * on Windows. The search stays out of the junction, and the program reached through it would
     * count once anyway (both have the same real path).
     */
    @Test
    void onWindowsAJunctionedInstallCountsOnce() throws IOException, InterruptedException {
        assumeTrue(PlatformUtil.isWindows(), "junctions are a Windows feature");
        Path exe = install("neovim/nvim-0.12.6", "nvim.exe");
        Path junction = junction(tmp.resolve("neovim/nvim-current"), tmp.resolve("neovim/nvim-0.12.6"));
        try {
            assertEquals(List.of(real(exe)), NvimExecutable.installsIn(tmp.resolve("neovim"), "nvim", true));
        } finally {
            removeJunction(junction);
        }
    }

    @Test
    void onWindowsAJunctionOutOfTheFolderIsNotFollowed() throws IOException, InterruptedException {
        // A junction to a Neovim elsewhere (or to a large folder, or to itself) is not searched. Two
        // checks in installsIn see to it, each enough alone: the folder's attributes (Java reports a
        // junction as "other", not as a plain folder) and its real path.
        assumeTrue(PlatformUtil.isWindows(), "junctions are a Windows feature");
        install("elsewhere/nvim-win64", "nvim.exe");
        Files.createDirectories(tmp.resolve("neovim"));
        Path junction = junction(tmp.resolve("neovim/nvim-win64"), tmp.resolve("elsewhere/nvim-win64"));
        try {
            assertTrue(Files.isRegularFile(junction.resolve("bin/nvim.exe")), "the junction leads to the install");
            assertEquals(List.of(), NvimExecutable.installsIn(tmp.resolve("neovim"), "nvim", true));
        } finally {
            removeJunction(junction);
        }
    }

    /** Creates {@code link} as a junction to the folder {@code target} with {@code mklink /J}. */
    private static Path junction(Path link, Path target) throws IOException, InterruptedException {
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        String output = new String(mklink.getInputStream().readAllBytes());
        assertEquals(0, mklink.waitFor(), "mklink /J failed: " + output);
        return link;
    }

    /**
     * Removes a junction itself, not what it leads to: {@code rmdir} on a junction deletes the link,
     * whereas a recursive delete (such as {@code @TempDir}'s cleanup) might walk into it first.
     */
    private static void removeJunction(Path junction) throws IOException, InterruptedException {
        new ProcessBuilder("cmd", "/c", "rmdir", junction.toString()).redirectErrorStream(true).start().waitFor();
    }

    @Test
    void aMissingFolderHasNoInstalls() {
        assertEquals(List.of(), NvimExecutable.installsIn(tmp.resolve("missing"), "nvim", false));
    }

    @Test
    void ofSeveralInstallsTheNewestNeovimWins() throws IOException {
        // Versions side by side, renamed so they can coexist; the names are not trusted, the programs are asked.
        Path older = install("neovim/nvim-0.13.0", "nvim"); // name says newer, program is older
        Path newer = install("neovim/nvim-0.12.6", "nvim");
        Map<Path, NeovimVersion> versions = Map.of(real(older), new NeovimVersion(0, 12, 6),
                real(newer), new NeovimVersion(0, 13, 1));
        assertEquals(new NvimExecutable.Found(real(newer), 2), findIn("neovim", versions::get));
    }

    @Test
    void installsThatDoNotRunAsNeovimAreSkippedAndNotCounted() throws IOException {
        // An archive for another processor cannot run here: its version is unknown (null).
        install("neovim/nvim-linux-arm64", "nvim");
        Path runs = install("neovim/nvim-linux-x86_64", "nvim");
        Map<Path, NeovimVersion> versions = Map.of(real(runs), new NeovimVersion(0, 12, 6));
        assertEquals(new NvimExecutable.Found(real(runs), 1), findIn("neovim", versions::get));
    }

    @Test
    void whenNoInstallRunsNoneIsChosen() throws IOException {
        install("neovim/nvim-a", "nvim");
        install("neovim/nvim-b", "nvim");
        assertNull(findIn("neovim", p -> null));
    }

    @Test
    void evenASingleInstallIsAskedItsVersion() throws IOException {
        install("neovim/nvim-linux-arm64", "nvim");
        assertNull(findIn("neovim", p -> null));
    }

    @Test
    void binNvimCompetesWithTheInstallsBelow() throws IOException {
        Path top = file("neovim/bin/nvim");
        Path below = install("neovim/nvim-0.13.0", "nvim");
        Map<Path, NeovimVersion> versions = Map.of(top.toAbsolutePath(), new NeovimVersion(0, 12, 6),
                real(below), new NeovimVersion(0, 13, 0));
        assertEquals(new NvimExecutable.Found(real(below), 2), findIn("neovim", versions::get));
    }

    @Test
    void onATieBinNvimWins() throws IOException {
        Path top = file("neovim/bin/nvim");
        install("neovim/nvim-0.12.6", "nvim");
        assertEquals(new NvimExecutable.Found(top.toAbsolutePath(), 2), findIn("neovim", ANY_FILE_RUNS));
    }

    @Test
    void anInstallFoldersOwnBinNvimCountsOnce() throws IOException {
        // C:\Program Files\Neovim: bin\nvim.exe is both the direct candidate and an install the search finds.
        Path exe = install("Neovim", "nvim.exe");
        assertEquals(new NvimExecutable.Found(exe.toAbsolutePath(), 1),
                NvimExecutable.findInFolder(tmp.resolve("Neovim"), "nvim.exe", true, true, ANY_FILE_RUNS));
    }

    @Test
    void aBinNvimThatDoesNotRunFallsBackToTheSearch() throws IOException {
        file("neovim/bin/nvim");
        Path below = install("neovim/nvim-linux-x86_64", "nvim");
        Map<Path, NeovimVersion> versions = Map.of(real(below), new NeovimVersion(0, 12, 6));
        assertEquals(new NvimExecutable.Found(real(below), 1), findIn("neovim", versions::get));
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

    // ---- the usual install folders: checked, not searched ----

    @Test
    void searchFoldersAreInstallFoldersToo() throws IOException {
        Path exe = file("homebrew/bin/nvim");
        assertEquals(exe.toAbsolutePath().toString(),
                resolve("", "", false, List.of(tmp.resolve("missing"), tmp.resolve("homebrew"))));
    }

    @Test
    void theUsualInstallFoldersAreNotSearched() throws IOException {
        // /usr/local, say, may hold a lot: only its own bin/nvim counts.
        install("usr-local/nvim-linux-x86_64", "nvim");
        assertEquals("nvim", resolve("", "", false, List.of(tmp.resolve("usr-local"))));
    }

    @Test
    void aUsualInstallFolderWhoseNvimDoesNotRunIsPassedOver() throws IOException {
        file("broken/bin/nvim");
        Path works = file("homebrew/bin/nvim");
        Function<Path, NeovimVersion> versionOf = p -> p.equals(works.toAbsolutePath()) ? new NeovimVersion(0, 12, 6)
                : null;
        assertEquals(works.toAbsolutePath().toString(), NvimExecutable.find("", "", false, null,
                List.of(tmp.resolve("broken"), tmp.resolve("homebrew")), versionOf).command());
    }

    @Test
    void theUsualInstallFoldersOnMacOsAndLinux() {
        String home = System.getProperty("user.home");
        List<Path> dirs = NvimExecutable.searchFolders(false);
        assertTrue(dirs.contains(Path.of(home, "bin")), "~/bin/nvim: " + dirs);
        assertTrue(dirs.contains(Path.of(home, ".local")), "~/.local/bin/nvim: " + dirs);
        assertFalse(dirs.contains(Path.of(home)), "not the home folder itself: " + dirs);
        assertFalse(dirs.contains(Path.of("/snap")), "not snap: " + dirs);
    }

    // ---- blank preference: the dedicated folder first ----

    @Test
    void blankUsesTheDedicatedFolderBeforePath() throws IOException {
        Path dedicated = file("opt/neovim/bin/nvim");
        Path onPath = file("path/nvim");
        assertEquals(dedicated.toAbsolutePath().toString(), resolve("", onPath.getParent().toString(),
                false, tmp.resolve("opt/neovim"), List.of()));
    }

    @Test
    void blankSearchesInsideTheDedicatedFolder() throws IOException {
        Path exe = install("opt/neovim/nvim-win64/nvim-win64", "nvim.exe");
        Path onPath = file("path/nvim.exe");
        assertEquals(real(exe).toString(), resolve("", onPath.getParent().toString(), true,
                tmp.resolve("opt/neovim"), List.of()));
    }

    @Test
    void blankFallsBackToPathWhenTheDedicatedNeovimDoesNotRun() throws IOException {
        file("opt/neovim/bin/nvim");
        Path onPath = pathDirectory("onpath"); // relative: an absolute Windows path has a ':' (see pathDirectory)
        try {
            Path exe = Files.createFile(onPath.resolve("nvim"));
            assertEquals(exe.toAbsolutePath().toString(), NvimExecutable.find("", onPath.toString(), false,
                    tmp.resolve("opt/neovim"), List.of(), p -> null).command());
        } finally {
            deleteTree(onPath);
        }
    }

    @Test
    void blankFallsBackToPathWhenTheDedicatedFolderIsEmpty() throws IOException {
        Path onPath = pathDirectory("onpath");
        try {
            Path exe = Files.createFile(onPath.resolve("nvim"));
            assertEquals(exe.toAbsolutePath().toString(), resolve(null, onPath.toString(), false,
                    tmp.resolve("opt/neovim"), List.of()));
        } finally {
            deleteTree(onPath);
        }
    }

    @Test
    void blankThenSearchesTheUsualFolders() throws IOException {
        Path exe = file("homebrew/bin/nvim");
        assertEquals(exe.toAbsolutePath().toString(), resolve("  ", "", false,
                tmp.resolve("opt/neovim"), List.of(tmp.resolve("homebrew"))));
    }

    @Test
    void anExplicitNameDoesNotUseTheDedicatedFolder() throws IOException {
        file("opt/neovim/bin/nvim");
        assertEquals("nvim", resolve("nvim", "", false, tmp.resolve("opt/neovim"), List.of()));
    }

    @Test
    void dedicatedFolderIsThePluginsNeovimFolder() {
        assertEquals(PluginFolders.dataHome().resolve("neovim4eclipse-plugin").resolve("neovim"),
                NvimExecutable.dedicatedFolder());
    }

    @Test
    void aLeadingTildeIsTheHomeFolder() {
        String home = System.getProperty("user.home");
        assertEquals(home + "/.local/share/neovim4eclipse-plugin/neovim", NvimExecutable.expandHome("~/.local/share/neovim4eclipse-plugin/neovim"));
        assertEquals(home, NvimExecutable.expandHome("~"));
        assertEquals("~user/x", NvimExecutable.expandHome("~user/x"), "only the current user's ~");
        assertEquals("/opt/~", NvimExecutable.expandHome("/opt/~"));
    }

    @Test
    void locateFindsTheFileBehindACommandName() throws IOException {
        // A relative PATH folder (pathDirectory): an absolute one, such as C:\Users\..., would be
        // cut at its drive letter's colon, and the rest found only when the build runs on drive C:.
        Path onPath = pathDirectory("path");
        try {
            Path onPathExe = Files.createFile(onPath.resolve("nvim"));
            assertEquals(onPathExe.toAbsolutePath(), NvimExecutable.locate("nvim", onPath.toString(), false));
        } finally {
            deleteTree(onPath);
        }
        Path exe = file("path/nvim");
        assertEquals(exe, NvimExecutable.locate(exe.toString(), "", false));
        assertNull(NvimExecutable.locate("nvim", "", false));
        assertNull(NvimExecutable.locate(tmp.resolve("missing").toString(), "", false));
    }
}
