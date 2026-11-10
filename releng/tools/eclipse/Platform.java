// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package eclipse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import common.EclipseCopy;
import common.Folders;
import common.Shell;
import common.ToolFailure;

/**
 * One Eclipse Platform, downloaded for this machine and unpacked into a folder of its own: the
 * Eclipse the tool reads ({@code eclipse.ini}, {@code plugins/}) and smoke-installs into. The
 * Platform is Eclipse's smallest download (about 100 MB, no Java tools), which is why CI's smoke
 * job uses it too.
 *
 * <p>The download itself is {@link common.Http#download}: checked against the SHA-512 the drop's
 * checksums file gives, both fetched over HTTPS from download.eclipse.org, and kept in
 * {@code target/eclipse-upgrade/downloads/} for the next run. Each operating system packs it
 * differently:
 * <ul>
 *   <li>Linux: a {@code .tar.gz} with an {@code eclipse/} folder, unpacked with {@code tar};</li>
 *   <li>Windows: a {@code .zip} with an {@code eclipse/} folder ({@link Folders#unzip}, which
 *       refuses any entry that would land outside the folder);</li>
 *   <li>macOS: a {@code .dmg} disk image with {@code Eclipse.app}, attached read-only with
 *       {@code hdiutil}, copied out with {@code ditto}, and detached again.</li>
 * </ul>
 */
public final class Platform {

    /** An unpacked Eclipse: the folder p2 installs into, and the program that starts it. */
    record Installation(Path folder, Path program) {

        /** The folder with {@code eclipse.ini} and {@code plugins/}: {@code Contents/Eclipse} in a macOS app. */
        Path inner() {
            return folder.resolve(EclipseCopy.inner(folder));
        }
    }

    private Platform() {
    }

    /**
     * Unpacks a checked download into {@code target}, emptied first, and finds the program.
     * Example on Linux: {@code target/eclipse/eclipse}; on macOS
     * {@code target/Eclipse.app/Contents/MacOS/eclipse}; on Windows {@code target/eclipse/eclipse.exe}.
     */
    static Installation unpack(Path archive, Path target, Shell shell) {
        Folders.deleteTree(target);
        try {
            Files.createDirectories(target);
            String name = archive.getFileName().toString();
            if (name.endsWith(".tar.gz")) {
                run(shell, List.of("tar", "-xzf", archive.toString(), "-C", target.toString()), "unpack " + name);
                return found(target.resolve("eclipse"), target.resolve("eclipse").resolve("eclipse"));
            }
            if (name.endsWith(".zip")) {
                Folders.unzip(archive, target);
                return found(target.resolve("eclipse"), target.resolve("eclipse").resolve("eclipse.exe"));
            }
            if (name.endsWith(".dmg")) {
                Path mount = target.resolveSibling(target.getFileName() + "-dmg");
                if (Files.isDirectory(mount)) {
                    // A run stopped while the image was attached leaves it there, read-only: detach it first.
                    shell.capture(List.of("hdiutil", "detach", mount.toString(), "-force", "-quiet"));
                }
                Folders.deleteTree(mount);
                run(shell, List.of("hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mount.toString(),
                        archive.toString()), "attach " + name);
                try {
                    run(shell, List.of("ditto", mount.resolve("Eclipse.app").toString(), target.resolve("Eclipse.app").toString()),
                            "copy Eclipse.app out of " + name);
                } finally {
                    shell.capture(List.of("hdiutil", "detach", mount.toString(), "-quiet"));
                }
                Path app = target.resolve("Eclipse.app");
                return found(app, app.resolve("Contents/MacOS/eclipse"));
            }
        } catch (IOException e) {
            throw new ToolFailure("cannot unpack " + archive + ": " + e.getMessage());
        }
        throw new ToolFailure("no way to unpack " + archive.getFileName() + " (.tar.gz, .zip and .dmg are known)");
    }

    private static Installation found(Path folder, Path program) {
        if (!Files.isRegularFile(program) || !EclipseCopy.isEclipse(folder)) {
            throw new ToolFailure("the unpacked Platform has no Eclipse at " + program);
        }
        return new Installation(folder, program);
    }

    private static void run(Shell shell, List<String> command, String what) {
        Shell.Result r = shell.capture(command);
        if (!r.ok()) {
            throw new ToolFailure("could not " + what + ": " + Shell.display(command) + "\n" + r.out() + r.err());
        }
    }
}
