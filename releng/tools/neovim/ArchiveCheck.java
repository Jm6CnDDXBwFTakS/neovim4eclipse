// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package neovim;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InstallProgress;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InstalledVersions;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimInstaller;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimPlatform;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimRelease;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimVersion;

/**
 * Installs each archive of a Neovim release as <i>Get Neovim</i> does, with the plug-in's own
 * {@code util/NeovimInstaller}, into a scratch folder. Part of {@code NeovimUpgrade.java}, which
 * starts it with the plug-in's compiled classes on the class path:
 * <pre>
 *   java --class-path plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/target/classes \
 *       releng/tools/neovim/ArchiveCheck.java &lt;data file&gt; &lt;archives folder&gt; &lt;work folder&gt;
 * </pre>
 * For each archive of the data file ({@code neovim-downloads.properties}), from the archives
 * folder (the updater kept them there), {@code NeovimInstaller.install} does what <i>Get Neovim</i>
 * does after its download: size and SHA-256 against the file, unpacking (its tar reader refuses
 * links and any entry outside the top folder), the tree hash the plug-in checks before every
 * start, the version {@code nvim --version} reports, the swap into place. Calling the installer
 * itself, not a copy of its steps, means a step it gains later is checked here too.
 *
 * <p>A Neovim of another platform cannot run here, so for those its version is taken from the
 * data file and the rest is checked; all six archives are installed, on any machine.
 *
 * <p>A separate program, run with its own class path, because the tool itself runs from source
 * without the plug-in (see {@code release/Release.java}). Exit status 0 when every check passed.
 */
public final class ArchiveCheck {

    private ArchiveCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: java --class-path <plug-in classes> releng/tools/neovim/ArchiveCheck.java <data file> <archives> <work>");
            System.exit(2);
        }
        NeovimRelease release;
        try (Reader in = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            release = NeovimRelease.parse(in);
        }
        Path archives = Path.of(args[1]).toAbsolutePath();
        Path work = Path.of(args[2]).toAbsolutePath();
        Optional<NeovimPlatform> here = NeovimPlatform.current();
        boolean ok = true;
        for (NeovimRelease.Archive archive : release.downloads().values()) {
            NeovimPlatform platform = archive.platform();
            Path file = archives.resolve(archive.asset());
            String name = platform.code() + " " + archive.asset();
            ok &= install(file, release, platform, work, here.filter(platform::equals).isPresent(), name);
        }
        System.exit(ok ? 0 : 1);
    }

    /**
     * {@code NeovimInstaller.install} for one archive into {@code work}. The version check runs
     * the unpacked {@code nvim --version} when {@code runsHere} (with the plug-in's own time
     * limits, {@code InstalledVersions}); for another platform's Neovim it takes the data file's.
     */
    private static boolean install(Path file, NeovimRelease release, NeovimPlatform platform, Path work, boolean runsHere,
            String name) {
        if (!Files.isRegularFile(file)) {
            return fail(name + ": not downloaded (" + file + ")");
        }
        Function<Path, NeovimVersion> versionOf = runsHere ? InstalledVersions::of : program -> release.version();
        try {
            NeovimInstaller.Result r = NeovimInstaller.install(file, release, platform, work, NeovimInstaller.SwapGate.FREE,
                    versionOf, InstallProgress.NONE);
            System.out.println("ok: " + name + ": Get Neovim's installer installs it (size, SHA-256, unpacking, tree hash"
                    + (runsHere ? ", and it runs: Neovim " + release.version() : "; another platform's, so not run here") + ") into "
                    + r.program());
            return true;
        } catch (RuntimeException e) {
            return fail(name + ": Get Neovim's installer refuses it: " + e.getMessage());
        }
    }

    private static boolean fail(String message) {
        System.err.println("FAILED: " + message);
        return false;
    }
}
