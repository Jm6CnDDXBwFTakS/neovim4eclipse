// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which Neovim a preference value names: the verified dedicated Neovim for a blank value, or the one
 * program an absolute path names; nothing is searched (NvimExecutable's class comment says why).
 */
class NvimExecutableTest {

    /** Stands in for {@code nvim --version}: every program runs as Neovim 0.12.6. */
    private static final Function<Path, NeovimVersion> RUNS = p -> new NeovimVersion(0, 12, 6);
    private static final Predicate<Path> ALL_VERIFIED = home -> true;
    private static final Predicate<Path> NONE_VERIFIED = home -> false;

    @TempDir
    Path tmp;

    private NvimExecutable.Resolved find(String configured, List<Path> homes, Predicate<Path> verified) {
        return NvimExecutable.find(configured, false, homes, verified, RUNS);
    }

    /** An install in {@code tmp/name}: bin/nvim and the runtime folder, as PluginNeovim.isInstall wants. */
    private Path install(String name) throws IOException {
        Path home = tmp.resolve(name);
        Files.createDirectories(home.resolve("bin"));
        Files.createFile(home.resolve("bin/nvim"));
        Files.createDirectories(home.resolve("share/nvim/runtime"));
        return home;
    }

    private Path file(String relative) throws IOException {
        Path f = tmp.resolve(relative);
        Files.createDirectories(f.getParent());
        return Files.createFile(f).toAbsolutePath();
    }

    // ---- blank: the dedicated Neovim, only if verified ----

    @Test
    void blankUsesTheVerifiedDedicatedNeovim() throws IOException {
        Path la = install("la");
        NvimExecutable.Resolved r = find("", List.of(la), ALL_VERIFIED);
        assertEquals(NvimExecutable.Kind.DEDICATED, r.kind());
        assertEquals(la.resolve("bin/nvim").toAbsolutePath().toString(), r.command());
        assertTrue(r.startable());
    }

    /** An older plug-in's Neovim, a damaged copy or a changed file: never run, not even --version. */
    @Test
    void anUnverifiedDedicatedNeovimIsNotRunAtAll() throws IOException {
        Path la = install("la");
        List<Path> asked = new ArrayList<>();
        NvimExecutable.Resolved r = NvimExecutable.find(null, false, List.of(la), NONE_VERIFIED, p -> {
            asked.add(p);
            return new NeovimVersion(0, 12, 6);
        });
        assertEquals(NvimExecutable.Kind.NO_DEDICATED, r.kind());
        assertFalse(r.startable());
        assertEquals(List.of(), asked, "no --version of an unchecked program");
    }

    @Test
    void theFirstVerifiedHomeThatRunsHereWins() throws IOException {
        Path ma = install("ma");
        Path mx = install("mx");
        assertEquals(mx.resolve("bin/nvim").toAbsolutePath().toString(),
                find("  ", List.of(ma, mx), home -> home.equals(mx)).command(), "ma fails the check");
        NvimExecutable.Resolved r = NvimExecutable.find("", false, List.of(ma, mx), ALL_VERIFIED,
                p -> p.startsWith(ma) ? null : new NeovimVersion(0, 12, 6));
        assertEquals(mx.resolve("bin/nvim").toAbsolutePath().toString(), r.command(),
                "a verified install for another processor that cannot run here is passed over");
    }

    @Test
    void withoutADedicatedNeovimTheExpectedProgramIsNamed() {
        Path la = tmp.resolve("la");
        NvimExecutable.Resolved r = find("", List.of(la), NONE_VERIFIED); // no install: PluginNeovim.verified is false
        assertEquals(NvimExecutable.Kind.NO_DEDICATED, r.kind());
        assertEquals(la.resolve("bin/nvim").toAbsolutePath().toString(), r.command());
        assertEquals("nvim.exe", NvimExecutable.find(null, true, List.of(), ALL_VERIFIED, RUNS).command());
    }

    /** Nothing else counts for a blank value: not PATH, not the usual folders, not a nearby install. */
    @Test
    void blankLooksNowhereElse() throws IOException {
        install("opt/nvim-linux-arm64");
        assertEquals(NvimExecutable.Kind.NO_DEDICATED, find("", List.of(tmp.resolve("la")), NONE_VERIFIED).kind());
    }

    // ---- a value: exactly one program ----

    @Test
    void anAbsoluteProgramIsUsedAsItIsUnchecked() throws IOException {
        Path program = file("custom/bin/nvim");
        NvimExecutable.Resolved r = find(program.toString(), List.of(), NONE_VERIFIED);
        assertEquals(NvimExecutable.Kind.PROGRAM, r.kind());
        assertEquals(program.toString(), r.command());
        assertTrue(r.startable());
    }

    /** A folder was a search root until 0.2.1; now nothing below it runs, the program must be named. */
    @Test
    void aFolderIsRefusedAndNothingInsideItIsUsed() throws IOException {
        Path folder = install("nvim-linux-arm64");
        NvimExecutable.Resolved r = find(folder.toString(), List.of(), ALL_VERIFIED);
        assertEquals(NvimExecutable.Kind.FOLDER, r.kind());
        assertFalse(r.startable(), "not even its bin/nvim, though that is a whole verified install");
    }

    @Test
    void aMissingProgramIsReportedAsMissing() {
        String missing = tmp.resolve("no/such/nvim").toString();
        assertEquals(NvimExecutable.Kind.MISSING, find(missing, List.of(), ALL_VERIFIED).kind());
    }

    @Test
    void aCommandNameOrRelativePathIsRefused() {
        assertEquals(NvimExecutable.Kind.NOT_ABSOLUTE, find("nvim", List.of(), ALL_VERIFIED).kind());
        assertEquals(NvimExecutable.Kind.NOT_ABSOLUTE, find("bin/nvim", List.of(), ALL_VERIFIED).kind());
    }

    @Test
    void aLeadingTildeIsTheHomeFolder() {
        String home = System.getProperty("user.home");
        assertEquals(home + "/.local/share/neovim4eclipse-plugin/neovim", NvimExecutable.expandHome("~/.local/share/neovim4eclipse-plugin/neovim"));
        assertEquals(home, NvimExecutable.expandHome("~"));
        assertEquals("~user/x", NvimExecutable.expandHome("~user/x"), "only the current user's ~");
        assertEquals("/opt/~", NvimExecutable.expandHome("/opt/~"));
    }

    // ---- why a start is refused ----

    @Test
    void eachRefusalSaysWhatToDo() {
        String folder = tmp.toString();
        assertEquals(folder + " is a folder: enter the Neovim program inside it, such as "
                + tmp.resolve("bin").resolve(NvimExecutable.defaultCommandName()),
                NvimExecutable.notFound(new NvimExecutable.Resolved(folder, NvimExecutable.Kind.FOLDER), folder)
                        .getMessage());
        assertEquals("No Neovim program at /no/nvim", NvimExecutable.notFound(
                new NvimExecutable.Resolved("/no/nvim", NvimExecutable.Kind.MISSING), " /no/nvim ").getMessage());
        assertEquals("The Neovim program must be an absolute path (a leading ~ is fine): nvim", NvimExecutable
                .notFound(new NvimExecutable.Resolved("nvim", NvimExecutable.Kind.NOT_ABSOLUTE), "nvim").getMessage());
        assertTrue(NvimExecutable.notFound(new NvimExecutable.Resolved("/d/la/bin/nvim", NvimExecutable.Kind.NO_DEDICATED),
                "").getMessage().startsWith("The dedicated Neovim is missing, or its files differ from Neovim "));
    }

    // ---- the command line ----

    @Test
    void embedCommandAddsEmbedFlagAndGuardThenExtraArgs() throws IOException {
        String program = file("bin/nvim").toString();
        assertEquals(List.of(program, "--embed", "--cmd", "let g:neovim4eclipse=1", "--clean"),
                NvimExecutable.embedCommand(program, List.of("--clean")));
    }

    @Test
    void embedCommandSkipsNullAndBlankExtraArgs() throws IOException {
        String program = file("bin/nvim").toString();
        assertEquals(List.of(program, "--embed", "--cmd", "let g:neovim4eclipse=1"),
                NvimExecutable.embedCommand(program, null));
        assertEquals(List.of(program, "--embed", "--cmd", "let g:neovim4eclipse=1", "-u"),
                NvimExecutable.embedCommand(program, java.util.Arrays.asList(null, " ", "-u")));
    }

    @Test
    void embedCommandRefusesWhatCannotStart() {
        assertThrows(NeovimNotFoundException.class, () -> NvimExecutable.embedCommand("nvim", null));
        assertThrows(NeovimNotFoundException.class, () -> NvimExecutable.embedCommand(tmp.toString(), null));
        assertThrows(NeovimNotFoundException.class,
                () -> NvimExecutable.embedCommand(tmp.resolve("missing").toString(), null));
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
    void defaultCommandNameMatchesPlatform() {
        assertEquals(PlatformUtil.isWindows() ? "nvim.exe" : "nvim", NvimExecutable.defaultCommandName());
    }

    @Test
    void dedicatedFolderIsThePluginsNeovimFolder() {
        assertEquals(PluginFolders.dataHome().resolve("neovim4eclipse-plugin").resolve("neovim"),
                NvimExecutable.dedicatedFolder());
    }

    @Test
    void locateGivesAnExistingAbsoluteFileOnly() throws IOException {
        Path exe = file("path/nvim");
        assertEquals(exe, NvimExecutable.locate(exe.toString()));
        assertNull(NvimExecutable.locate("nvim"), "a command name is never looked up");
        assertNull(NvimExecutable.locate(tmp.resolve("missing").toString()));
        assertNull(NvimExecutable.locate(null));
    }
}
