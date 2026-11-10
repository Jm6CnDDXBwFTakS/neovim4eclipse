// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private static final Pattern REFUSED = Pattern.compile("(?m)^\\[GNUPG:\\] (BADSIG|ERRSIG|REVKEYSIG|EXPSIG|NO_PUBKEY)\\b");

    /** Runs gpg inside its home folder: every path gpg sees is a plain name there (see {@link #prepare}). */
    private final Shell gpg;
    private final Path home;

    private Signatures(Path home) {
        this.gpg = new Shell(home);
        this.home = home;
    }

    /**
     * The check, ready: a keyring of its own holding only the keys of {@code keysUrl}; empty when
     * {@code gpg} is not installed.
     *
     * <p>Every path gpg is given is relative to its home folder, which is also its working folder
     * ({@code --homedir .}, {@code KEYS}, {@code apache-maven-3.9.16-bin.zip}). Why: Git for Windows
     * ships a gpg built for its Unix layer (MSYS), which does not read {@code C:\Users\…} as an
     * absolute path and joins it to its current folder instead; a plain name means the same to it,
     * to Gpg4win's gpg and to gpg on Linux and macOS. The folder is a short temporary one, not one
     * under {@code target/}: GnuPG 2.4 may put sockets in it, and macOS limits a socket's path to 104
     * bytes. It is deleted when the tool ends.
     */
    static Optional<Signatures> prepare(String keysUrl) {
        if (!Shell.onPath("gpg")) {
            return Optional.empty();
        }
        Signatures s;
        try {
            Path home = Files.createTempDirectory("n4e-gpg");
            s = new Signatures(home);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                // gpg may have started a helper (gpg-agent, keyboxd) for this folder: stop it first.
                s.gpg.capture(List.of("gpgconf", "--homedir", ".", "--kill", "all"));
                Folders.deleteTree(home);
            }));
            Files.writeString(home.resolve("KEYS"), Http.text(keysUrl));
        } catch (IOException e) {
            throw new ToolFailure("cannot prepare the PGP check: " + e.getMessage());
        }
        Shell.Result r = s.gpg.capture(importCommand());
        if (!r.ok()) {
            throw new ToolFailure("gpg could not import " + keysUrl + ":\n" + r.err());
        }
        return Optional.of(s);
    }

    /**
     * Checks {@code file} against its signature {@code url + ".asc"}; returns who signed it. Both
     * go into gpg's folder under the file's own name for the check (a copy: the plain names above),
     * and are removed again after it.
     */
    String verify(Path file, String url) {
        String name = file.getFileName().toString();
        Path copy = home.resolve(name);
        Path asc = home.resolve(name + ".asc");
        try {
            Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
            String signature = Http.text(url + ".asc");
            if (!isDetachedSignature(signature)) {
                // A signed message instead would carry its own data, which an old gpg checks in place of the archive.
                throw new ToolFailure(url + ".asc is not a detached PGP signature; " + name + " was not used");
            }
            Files.writeString(asc, signature);
            Shell.Result r = gpg.capture(verifyCommand(name));
            return signer(r.out()).orElseThrow(() -> new ToolFailure(name + ": its PGP signature does not check against "
                    + "a key of Maven's KEYS file; it was not used.\n" + r.out() + "\n" + r.err()));
        } catch (IOException e) {
            throw new ToolFailure("cannot prepare the PGP check of " + name + ": " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(copy);
                Files.deleteIfExists(asc);
            } catch (IOException e) {
                System.out.println("  (could not delete " + copy + ": " + e.getMessage() + ")");
            }
        }
    }

    /** Whether {@code asc} is an armored detached signature, as Maven publishes: {@code -----BEGIN PGP SIGNATURE-----}. */
    static boolean isDetachedSignature(String asc) {
        return asc.strip().startsWith("-----BEGIN PGP SIGNATURE-----");
    }

    /** The import of Maven's keys into the keyring, run in gpg's home folder: plain names only. */
    static List<String> importCommand() {
        return List.of("gpg", "--homedir", ".", "--batch", "--quiet", "--import", "KEYS");
    }

    /** The check of {@code name} against {@code name.asc}, both in gpg's home folder: plain names only. */
    static List<String> verifyCommand(String name) {
        if (name.isEmpty() || name.startsWith("-") || name.contains("/") || name.contains("\\") || name.contains(":")) {
            throw new IllegalArgumentException("not a plain file name: " + name); // the archives' names never are
        }
        return List.of("gpg", "--homedir", ".", "--batch", "--status-fd", "1", "--verify", name + ".asc", name);
    }

    /**
     * Who made a good signature, from {@code gpg --status-fd}'s lines: the key's fingerprint and
     * name, or empty when the lines show no {@code VALIDSIG}, or show a bad signature, an expired
     * signature, a revoked key or an unknown one. A key that has expired since it signed ({@code EXPKEYSIG}) still
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
