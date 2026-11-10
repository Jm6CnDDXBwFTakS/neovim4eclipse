// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import common.Folders;
import common.Http;
import common.Shell;
import common.ToolFailure;

/**
 * The PGP check of what the Maven tool downloads, made with {@code gpg} when it is installed.
 *
 * <p>Why on top of the hashes: Central's {@code .sha512} or {@code .sha1} file comes from the
 * same server as the archive, so it shows the download arrived whole, not that the Maven project
 * published it. Every Maven release is also signed ({@code apache-maven-3.9.16-bin.zip.asc}) by a
 * release manager whose key is in Maven's {@code KEYS} file, which apache.org publishes on another
 * server ({@code https://downloads.apache.org/maven/KEYS}, the address in
 * {@code releng/tools/maven/settings.properties}). A signature that checks against a key from
 * that file ties the archive to the project.
 *
 * <p>How: the {@code KEYS} file goes into a keyring of the tool's own (a new temporary folder each
 * run, deleted when the tool ends), never the developer's, so nothing the
 * developer trusts counts and nothing is added to their keyring. Then
 * {@code gpg --status-fd 1 --verify file.asc file} must report {@code VALIDSIG}: a good signature by
 * a key in that keyring. Example of the lines read:
 * <pre>
 *   [GNUPG:] GOODSIG 9EB80E92EB2135B1 Slawomir Jaranowski &lt;sjaranowski@apache.org&gt;
 *   [GNUPG:] VALIDSIG 84789D24DF77A32433CE1F079EB80E92EB2135B1 2026-05-13 …
 * </pre>
 * Without {@code gpg} the check is skipped and the tool says so; the hashes still apply.
 */
final class Signatures {

    private static final Pattern VALIDSIG = Pattern.compile("(?m)^\\[GNUPG:\\] VALIDSIG ([0-9A-F]{40}) ");
    private static final Pattern SIGNER = Pattern.compile("(?m)^\\[GNUPG:\\] (?:GOODSIG|EXPKEYSIG) [0-9A-F]+ (.+)$");
    private static final Pattern REFUSED = Pattern.compile("(?m)^\\[GNUPG:\\] (BADSIG|ERRSIG|REVKEYSIG|NO_PUBKEY)\\b");

    private final Shell shell;
    private final Path home;

    private Signatures(Shell shell, Path home) {
        this.shell = shell;
        this.home = home;
    }

    /**
     * The check, ready: a keyring of its own holding only the keys of {@code keysUrl} (saved as {@code work}/KEYS);
     * empty when {@code gpg} is not installed.
     */
    static Optional<Signatures> prepare(Shell shell, Path work, String keysUrl) {
        if (!Shell.onPath("gpg")) {
            return Optional.empty();
        }
        Path home;
        try {
            // A short temporary folder, not one under target/: GnuPG 2.4 may put sockets in its home
            // folder, and macOS limits a socket's path to 104 bytes. Deleted when the tool ends.
            home = Files.createTempDirectory("n4e-gpg");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                // gpg may have started a helper (gpg-agent, keyboxd) for this folder: stop it first.
                shell.capture(List.of("gpgconf", "--homedir", home.toString(), "--kill", "all"));
                Folders.deleteTree(home);
            }));
            Files.createDirectories(work); // the first run has no target/maven-upgrade yet
            Path keys = Files.writeString(work.resolve("KEYS"), Http.text(keysUrl));
            Shell.Result r = shell.capture(List.of("gpg", "--homedir", home.toString(), "--batch", "--quiet", "--import", keys.toString()));
            if (!r.ok()) {
                throw new ToolFailure("gpg could not import " + keysUrl + ":\n" + r.err());
            }
        } catch (IOException e) {
            throw new ToolFailure("cannot prepare the PGP check: " + e.getMessage());
        }
        return Optional.of(new Signatures(shell, home));
    }

    /** Checks {@code file} against {@code file.asc} from {@code url + ".asc"}; returns who signed it. */
    String verify(Path file, String url) {
        Path asc = file.resolveSibling(file.getFileName() + ".asc");
        try {
            Files.writeString(asc, Http.text(url + ".asc"));
        } catch (IOException e) {
            throw new ToolFailure("cannot write " + asc + ": " + e.getMessage());
        }
        Shell.Result r = shell.capture(List.of("gpg", "--homedir", home.toString(), "--batch", "--status-fd", "1", "--verify",
                asc.toString(), file.toString()));
        return signer(r.out()).orElseThrow(() -> new ToolFailure(file.getFileName() + ": its PGP signature does not check against "
                + "a key of Maven's KEYS file; it was not used.\n" + r.out() + "\n" + r.err()));
    }

    /**
     * Who made a good signature, from {@code gpg --status-fd}'s lines: the key's fingerprint and
     * name, or empty when the lines show no {@code VALIDSIG}, or show a bad signature, a revoked
     * key or an unknown one. A key that has expired since it signed ({@code EXPKEYSIG}) still
     * counts: release managers' keys expire, the releases they signed do not.
     */
    static Optional<String> signer(String status) {
        Matcher valid = VALIDSIG.matcher(status);
        if (REFUSED.matcher(status).find() || !valid.find()) {
            return Optional.empty();
        }
        Matcher name = SIGNER.matcher(status);
        return Optional.of((name.find() ? name.group(1).strip() + ", " : "") + "key " + valid.group(1));
    }
}
