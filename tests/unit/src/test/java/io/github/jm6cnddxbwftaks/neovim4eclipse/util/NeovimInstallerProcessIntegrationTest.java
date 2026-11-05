// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;

/**
 * Get Neovim's swap waits while <em>another program</em> runs a Neovim from the folder, asked of the
 * real process list ({@link NeovimProcesses#runFrom}), not of a stand-in.
 *
 * <p>This replaces the manual check "A second Eclipse holds the swap" (gn-two-eclipses): there, a
 * second Eclipse on the same plug-in folder runs {@code nvim} from {@code neovim/la} while the first
 * reinstalls. What the first Eclipse sees of the second is only that: a process whose program lies
 * inside {@code la}. So the test starts exactly such a process, a copy of the tests' real Neovim
 * ({@code -Dnvim.path}) inside a temporary {@code la}, kept alive by {@code --embed} (it waits on
 * standard input), then installs over it. {@link NeovimInstallerTest#aNeovimOfAnotherProcessHoldsTheSwap}
 * checks the same rule with a predicate; this one checks that the operating system's answer reaches it.
 *
 * <p>Why a copy: the tests' Neovim is often the user's own dedicated install, and a swap over it
 * would replace it. The copy lives in {@code @TempDir} and nothing outside it is moved.
 */
@Tag("integration")
class NeovimInstallerProcessIntegrationTest {

    private static final NeovimPlatform LA = NeovimPlatform.byCode("la").orElseThrow();
    private static final NeovimVersion VERSION = new NeovimVersion(0, 12, 6);

    @TempDir
    Path tmp;

    private Process other;

    @AfterEach
    void stopTheOtherNeovim() throws InterruptedException {
        if (other != null && other.isAlive()) {
            other.destroyForcibly().waitFor(TestWait.millis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void aNeovimAnotherProgramRunsFromTheFolderHoldsTheSwapUntilItEnds() throws Exception {
        Path program = NvimExecutable.locate(NvimExecutable.resolve(TestNeovim.PATH));
        assumeTrue(program != null, "nvim not available");
        Path realHome = program.getParent().getParent(); // <home>/bin/nvim
        Path folder = tmp.resolve("neovim");
        Path la = folder.resolve("la");
        copyTree(realHome, la);
        Path copied = la.resolve(realHome.relativize(program));
        // The new copy has a bin/nvim too, so a file only the running copy has tells them apart.
        Path marker = Files.writeString(la.resolve("running-copy"), "the other Eclipse's Neovim");

        // The "second Eclipse": a Neovim started from inside la, alive until its input closes.
        other = new ProcessBuilder(copied.toString(), "--embed", "--headless", "--clean", "-n")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        waitUntil("the copied Neovim shows in the process list", () -> NeovimProcesses.runFrom(la));

        Path archive = TestArchives.neovimTarGz(tmp.resolve("nvim-linux-arm64.tar.gz"), "nvim-linux-arm64");
        NeovimRelease release = NeovimRelease.parse(new StringReader("""
                version=0.12.6
                url=https://github.com/neovim/neovim/releases/download/v{version}/{asset}
                la.asset=nvim-linux-arm64.tar.gz
                la.size=%d
                la.sha256=%s
                la.tree=%s
                """.formatted(Files.size(archive), NeovimInstaller.sha256(archive, InstallProgress.NONE),
                TestArchives.neovimTree())));

        // FREE: this Eclipse runs nothing from la; only the other process does.
        NeovimInstaller.Result result = NeovimInstaller.install(archive, release, LA, folder,
                NeovimInstaller.SwapGate.FREE, p -> VERSION, InstallProgress.NONE);
        assertEquals(NeovimInstaller.Outcome.WAITS_FOR_RESTART, result.outcome(), "the other Neovim holds the swap");
        assertTrue(Files.exists(marker), "the running Neovim's folder is untouched");
        NeovimInstaller.finishPending(folder);
        assertTrue(Files.exists(marker), "a start while it runs does not swap either");

        // The "second Eclipse" closes: its Neovim ends, and the next start swaps the new copy in.
        other.getOutputStream().close();
        assertTrue(other.waitFor(TestWait.millis(), TimeUnit.MILLISECONDS), "the copied Neovim ended");
        waitUntil("no process runs from la any more", () -> !NeovimProcesses.runFrom(la));
        NeovimInstaller.finishPending(folder);
        assertFalse(Files.exists(marker), "the copied Neovim was swapped out");
        assertTrue(Files.isDirectory(folder.resolve("la/bin")), "the new copy is in la");
    }

    /** Waits with {@link TestWait}'s limit for {@code condition}; fails naming {@code what}. */
    private static void waitUntil(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TestWait.millis();
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "timed out waiting: " + what);
            Thread.sleep(20);
        }
    }

    /** Copies a folder with its files' attributes (the execute bit; on macOS the code signature). */
    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path source : (Iterable<Path>) paths::iterator) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }
}
