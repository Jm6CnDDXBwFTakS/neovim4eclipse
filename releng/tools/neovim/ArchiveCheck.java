// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package neovim;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ArchiveExtractor;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InstallProgress;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InstallTree;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimPlatform;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimRelease;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PluginNeovim;

/**
 * Unpacks each archive of a Neovim release the way <i>Get Neovim</i> does, with the plug-in's own
 * classes, and runs this machine's Neovim. Part of {@code NeovimUpgrade.java}, which starts it with
 * the plug-in's compiled classes on the class path:
 * <pre>
 *   java --class-path plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/target/classes \
 *       releng/tools/neovim/ArchiveCheck.java &lt;data file&gt; &lt;archives folder&gt; &lt;work folder&gt;
 * </pre>
 * For each archive of the data file ({@code neovim-downloads.properties}), from the archives
 * folder (the tool downloaded them there): its size and SHA-256 against the file, then
 * {@code util/ArchiveExtractor} unpacks it into {@code <work>/<code>}, and {@code util/InstallTree}
 * must give the file's tree hash, which the plug-in checks before every start. Then this
 * machine's {@code bin/nvim --version} must print the release's version.
 *
 * <p>Why the plug-in's classes and not a copy: what matters is whether <i>Get Neovim</i> can
 * install the new archives. Its tar reader refuses links and any entry outside the top folder,
 * which Neovim's archives have not had so far; a new release that adds one would otherwise be
 * found only by the first user who presses <i>Get Neovim</i>. All six are checked, so a macOS or
 * Windows archive is checked on Linux too.
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
        Path archives = Path.of(args[1]);
        Path work = Path.of(args[2]);
        boolean ok = true;
        for (NeovimRelease.Archive archive : release.downloads().values()) {
            ok &= check(archive, archives.resolve(archive.asset()), work.resolve(archive.platform().code()));
        }
        Optional<NeovimPlatform> here = NeovimPlatform.current();
        if (ok && here.isPresent() && release.forPlatform(here.get()).isPresent()) {
            ok = runs(PluginNeovim.program(work.resolve(here.get().code()), here.get().windows()), release.version().toString());
        } else if (here.isEmpty()) {
            System.out.println("note: this machine is none of Neovim's platforms, so no Neovim was run");
        }
        System.exit(ok ? 0 : 1);
    }

    /** One archive: size, SHA-256, unpacked by the plug-in, its tree hash. */
    private static boolean check(NeovimRelease.Archive archive, Path file, Path home) throws Exception {
        String name = archive.platform().code() + " " + archive.asset();
        if (!Files.isRegularFile(file)) {
            return fail(name + ": not downloaded (" + file + ")");
        }
        if (Files.size(file) != archive.size() || !archive.sha256().equals(sha256(file))) {
            return fail(name + ": its size or SHA-256 differs from the data file");
        }
        try {
            ArchiveExtractor.extract(file, archive.kind(), home, InstallProgress.NONE);
        } catch (IOException | RuntimeException e) {
            return fail(name + ": Get Neovim's extractor refuses it: " + e.getMessage());
        }
        Optional<String> tree = InstallTree.of(home);
        if (tree.isEmpty()) {
            return fail(name + ": unpacked, but it holds a link or another special file, which the plug-in refuses to run");
        }
        if (!tree.get().equals(archive.tree())) {
            return fail(name + ": unpacked, but its tree hash " + tree.get() + " is not the data file's " + archive.tree());
        }
        System.out.println("ok: " + name + ": Get Neovim's extractor unpacks it, tree hash as in the data file");
        return true;
    }

    /** Whether {@code program --version} prints {@code NVIM v<version>} within a minute. */
    private static boolean runs(Path program, String version) throws Exception {
        Process p = new ProcessBuilder(List.of(program.toString(), "--version")).redirectErrorStream(true).start();
        p.getOutputStream().close();
        String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return fail(program + " --version gave no answer within a minute");
        }
        String first = out.lines().findFirst().orElse("");
        if (!first.startsWith("NVIM v" + version)) {
            return fail(program + " --version printed \"" + first + "\", not NVIM v" + version);
        }
        System.out.println("ok: this machine's Neovim runs: " + first);
        return true;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0;) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean fail(String message) {
        System.err.println("FAILED: " + message);
        return false;
    }
}
