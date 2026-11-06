// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimPlatform;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PluginFolders;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PluginNeovim;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ProcessorProbe;

/**
 * Puts the dedicated Neovim into the UI tests' own data folder, or takes it away, so a test can
 * check both cases on every machine. Before, the tests read the machine's state: a developer's
 * machine has the dedicated Neovim (Get Neovim installed it), a CI runner has none, so each covered
 * only one case, and a test written on one failed on the other.
 *
 * <p>"Installed" is a copy of the release folder that {@code -Dnvim.path} points into
 * ({@code <release>/bin/nvim}): in CI and with {@code java releng/tools/LikeCi.java}, the pinned
 * release, whose files pass the plug-in's check ({@link PluginNeovim#verified}). The copy goes to
 * {@code neovim/<code>/} of {@link PluginFolders#neovimFolder()}, which the test pom points under
 * {@code target/} (XDG_DATA_HOME). Nothing is changed outside {@code test.isolated.root}: the
 * developer's own dedicated Neovim is never touched, even by a pom that lost the isolation.
 *
 * <p>Example: {@code DedicatedNeovim.install()} in a test, {@code DedicatedNeovim.remove()} in
 * its {@code @AfterEach}; both forget the plug-in's kept check results, so the page computes its
 * note again.
 */
public final class DedicatedNeovim {

    private DedicatedNeovim() {
    }

    /** The folder the dedicated Neovim for this machine's processor goes in: {@code neovim/la}. */
    public static Path home() {
        Path home = PluginNeovim.home(PluginFolders.neovimFolder(), platform()).toAbsolutePath().normalize();
        String root = System.getProperty("test.isolated.root");
        assertTrue(root != null && !root.isBlank() && home.startsWith(Path.of(root).toAbsolutePath().normalize()),
                "refusing to change " + home + ": it is not under the tests' own folder " + root
                        + " (XDG_DATA_HOME in the test pom)");
        return home;
    }

    /** This machine's processor, as the plug-in sees it ({@link ProcessorProbe}). */
    private static NeovimPlatform platform() {
        return ProcessorProbe.await().orElseThrow(() -> new IllegalStateException("unknown processor"));
    }

    /**
     * Installs the dedicated Neovim: a copy of {@code -Dnvim.path}'s release folder. Skips the test
     * (with the reason) if that is not the pinned release, as when it names a system Neovim:
     * the copy would fail the check, so "installed" cannot be made.
     */
    public static void install() {
        Path home = home();
        Path release = release();
        remove();
        copyTree(release, home);
        PluginNeovim.forget();
        GetNeovimOffer.recheck();
        assumeTrue(PluginNeovim.verified(home, platform().windows()),
                "-Dnvim.path is not in the pinned release's folder (" + release + "), so the dedicated "
                        + "Neovim cannot be installed for this test");
    }

    /** Removes the dedicated Neovim, if a test installed one. */
    public static void remove() {
        Path home = home();
        deleteTree(home);
        PluginNeovim.forget();
        GetNeovimOffer.recheck();
    }

    /** The release folder of {@code -Dnvim.path}: {@code <release>/bin/nvim} gives {@code <release>}. */
    private static Path release() {
        String path = System.getProperty("nvim.path");
        assumeTrue(path != null && !path.isBlank() && !path.startsWith("${"), "no -Dnvim.path");
        Path bin = Path.of(path).toAbsolutePath().normalize().getParent();
        Path release = bin == null ? null : bin.getParent();
        assumeTrue(release != null && Files.isDirectory(release.resolve("share").resolve("nvim").resolve("runtime")),
                "-Dnvim.path " + path + " is not <release>/bin/nvim of a Neovim release folder");
        return release;
    }

    private static void copyTree(Path from, Path to) {
        try {
            Files.walkFileTree(from, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    // COPY_ATTRIBUTES keeps the execute bit of bin/nvim.
                    Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot copy " + from + " to " + to, e);
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                    if (e != null) {
                        throw e;
                    }
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + root, e);
        }
    }
}
