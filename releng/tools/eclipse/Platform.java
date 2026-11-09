// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package eclipse;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
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
 * <p>Every download is checked against the SHA-512 the drop's checksums file gives, both fetched
 * over HTTPS from download.eclipse.org; a download kept from an earlier run
 * ({@code target/eclipse-upgrade/downloads/}) is used again only if it still matches. Each
 * operating system packs it differently:
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

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private Platform() {
    }

    /** The body of an HTTPS GET of {@code url}, which must answer 200 (the Release tool reads data.json with it too). */
    public static String text(String url) {
        try {
            HttpResponse<String> r = HTTP.send(request(url), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) {
                throw new ToolFailure("GET " + url + " answered " + r.statusCode());
            }
            return r.body();
        } catch (IOException e) {
            throw new ToolFailure("GET " + url + " failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("interrupted");
        }
    }

    /** {@link #text}, or {@code null} for a 404: used to try the checksums file's two names. */
    static String textOrNull(String url) {
        try {
            HttpResponse<String> r = HTTP.send(request(url), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return r.statusCode() == 200 ? r.body() : null;
        } catch (IOException e) {
            throw new ToolFailure("GET " + url + " failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("interrupted");
        }
    }

    private static HttpRequest request(String url) {
        if (!url.startsWith("https://")) {
            throw new ToolFailure("only HTTPS is fetched: " + url); // a checksum fetched over HTTP proves nothing
        }
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).build();
    }

    /**
     * {@code url} in {@code cache}, checked against {@code sha512}: a file already there is kept
     * if it matches, else fetched again. The download goes to a {@code .part} file first, so a
     * stopped run never leaves a half file under the real name.
     */
    static Path download(String url, String sha512, Path cache) {
        Path file = cache.resolve(url.substring(url.lastIndexOf('/') + 1));
        if (Files.isRegularFile(file) && sha512(file).equals(sha512)) {
            System.out.println("ok: " + file.getFileName() + " from an earlier run, SHA-512 as the drop says");
            return file;
        }
        Path part = file.resolveSibling(file.getFileName() + ".part");
        try {
            Files.createDirectories(cache);
            System.out.println("Downloading " + url + " ...");
            HttpResponse<Path> r = HTTP.send(request(url), HttpResponse.BodyHandlers.ofFile(part));
            if (r.statusCode() != 200) {
                throw new ToolFailure("GET " + url + " answered " + r.statusCode());
            }
            String got = sha512(part);
            if (!got.equals(sha512)) {
                throw new ToolFailure(url + " has SHA-512 " + got + ", but the drop's checksums file says " + sha512
                        + "; nothing was unpacked");
            }
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ToolFailure("download of " + url + " failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("interrupted");
        } finally {
            try {
                Files.deleteIfExists(part); // gone after the move; left only by a failure: an error page, half a file
            } catch (IOException e) {
                System.out.println("  (could not delete " + part + ": " + e.getMessage() + ")");
            }
        }
        System.out.println("ok: " + file.getFileName() + ", SHA-512 as the drop says");
        return file;
    }

    static String sha512(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0;) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new ToolFailure("cannot read " + file + ": " + e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every Java has SHA-512
        }
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
