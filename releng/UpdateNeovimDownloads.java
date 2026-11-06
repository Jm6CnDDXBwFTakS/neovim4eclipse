// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Keeps {@code neovim-downloads.properties}, the data file behind the plug-in's <i>Get Neovim</i>
 * entry, in step with a Neovim release. A single-file Java program, so it runs the same on Linux,
 * macOS and Windows with only the JDK the build already needs (no {@code gh}, {@code jq} or Python).
 *
 * <h2>Usage</h2>
 * <pre>
 *   java releng/UpdateNeovimDownloads.java 0.13.0     rewrite the file for Neovim 0.13.0
 *   java releng/UpdateNeovimDownloads.java 0.13.0 --allow-young   the same for a release under 7 days old
 *   java releng/UpdateNeovimDownloads.java 0.13.0 --keep target/archives
 *                                                    the same, keeping the six archives there (the Neovim
 *                                                    tool checks them next, without downloading them again)
 *   java releng/UpdateNeovimDownloads.java --check    verify the file as it is; writes nothing
 *   java releng/UpdateNeovimDownloads.java --virustotal   ask VirusTotal about each hash (needs VT_API_KEY)
 *   java releng/UpdateNeovimDownloads.java install auto target/ci-neovim
 *                                                    install the pinned Neovim there (CI, LikeCi)
 * </pre>
 *
 * <h2>{@code install <code> <folder>}</h2>
 * Downloads the pinned archive for one platform ({@code lx la mx ma wx wa}, or {@code auto} for the
 * machine it runs on), checks its size, its SHA-256 and the tree hash of the files inside it against
 * the data file, unpacks it into {@code <folder>} (through {@code <top>.unpacking}, replacing an older
 * copy whole) and checks the unpacked files' tree hash again, runs
 * {@code nvim --version}, and prints the program's path (and nothing else) on standard output. Every
 * CI job gets its Neovim this way, so CI tests exactly the Neovim that <i>Get Neovim</i> installs
 * and checks the files GitHub serves now against the pin. A copy already in {@code <folder>} whose
 * files have the pinned tree hash (an earlier LikeCi run) is kept, with no download; a CI runner
 * starts empty, so CI always downloads. Example, in a workflow step:
 * <pre>
 *   nvim=$(java releng/UpdateNeovimDownloads.java install auto "$RUNNER_TEMP")
 *   # /home/runner/work/_temp/nvim-linux-x86_64/bin/nvim
 * </pre>
 * Why Java and not a shell script (it was {@code releng/install-neovim.sh} until 0.2.2): the script
 * computed the tree hash from the output of {@code find}, {@code sort}, {@code xargs} and
 * {@code sha256sum}, whose formats differ between GNU, macOS and Git for Windows, and changed under
 * the CI runner twice in one day (a backslash before the hash of a path holding one, then
 * {@code <hash> *<path>} lines). Here the rule is {@link #treeHash}, the code that pinned the hashes.
 * Downloads are retried ({@link #DOWNLOAD_ATTEMPTS}), as one dropped connection should not fail a
 * build. Only unpacking a tar archive uses a program, {@code tar}, as Java's own classes cannot set
 * the execute bit that {@code bin/nvim} needs.
 *
 * <h2>What a rewrite does</h2>
 * <ol>
 *   <li>Takes the asset names from the current file. Neovim renames an asset now and then (0.10.4
 *       renamed {@code nvim-linux64} to {@code nvim-linux-x86_64}); that is the one hand edit, and
 *       the old name then fails here with a 404 that says so.</li>
 *   <li>Downloads each of the six archives and computes its size, its SHA-256 and the <em>tree
 *       hash</em> of the files inside it: what the plug-in checks the unpacked Neovim against at
 *       every start (the plug-in's {@code util/InstallTree} has the rule; {@link #treeHash} here
 *       must give the same answer, which {@code install} confirms in every CI run, from the unpacked files).</li>
 *   <li>Reads the release's entry in GitHub's API and requires GitHub's own {@code digest} and size
 *       to match. That catches a download damaged or changed on its way (a proxy, a broken mirror),
 *       but it is not a second, independent source: GitHub computes the digest from whatever file
 *       was uploaded, so an asset replaced on GitHub has a matching digest too.</li>
 *   <li>Refuses a release published less than {@link #MIN_AGE} ago, unless {@code --allow-young}
 *       follows the version.</li>
 *   <li>Writes the file, and only if every step passed, then prints each archive's VirusTotal page.</li>
 * </ol>
 *
 * <h2>What the hashes can and cannot vouch for</h2>
 * The pinned hash is trusted on first use: it says "the file GitHub served when this was run", not
 * "the file Neovim's maintainers built". Neovim publishes no checksum file, signature or GitHub
 * build attestation for its archives (checked for v0.12.6: {@code gh api
 * repos/neovim/neovim/attestations/sha256:<hash>} answers 404, and the release has only the
 * archives, AppImages and MSIs), so there is nothing else to compare with. What narrows the risk:
 * <ul>
 *   <li>Waiting: a release is pinned only once it is a week old. A replaced asset is usually
 *       noticed and reported within days (Neovim's users download each release by the thousand).</li>
 *   <li>Looking: the VirusTotal pages printed after a rewrite, and {@code --virustotal}, show what
 *       the antivirus engines and the community say about exactly these files.</li>
 *   <li>Checking again: {@code --check} at every release, and CI's {@code install} at
 *       every run, compare the files GitHub serves now with the pinned hashes, so a file replaced
 *       after pinning is caught.</li>
 * </ul>
 * If Neovim starts publishing signatures or attestations, verify them here instead.
 * {@code NeovimVersion.MINIMUM} is read from the file, so it moves with a rewrite. A rewrite is
 * usually one step of {@code java releng/tools/neovim/NeovimUpgrade.java <version>}, which also
 * rewrites the docs' copies of the number ({@code releng/version-copies.txt}), adds the changelog
 * entry, unpacks the new archives with the plug-in's own extractor, and runs the tests
 * (docs/UPGRADING.md, "Neovim").
 *
 * <h2>{@code --check}</h2>
 * The same downloads and comparisons for the version already in the file, without writing. The
 * release workflow runs it at each tag push, before anything is published, so a release never ships
 * a hash that no longer matches GitHub (about 70 MB of downloads once per release). It covers the
 * three archives that CI never downloads.
 *
 * <h2>{@code --virustotal}</h2>
 * A manual, opt-in tool for the maintainer: with a personal VirusTotal API key in {@code VT_API_KEY},
 * it asks VirusTotal ({@code GET /api/v3/files/<sha256>}) about each of the six hashes, 15 seconds
 * apart (the free key allows 4 requests a minute), and prints the latest analysis counts. An archive
 * VirusTotal does not know is named with the upload page, so a report exists for users who follow the
 * plug-in's <i>VirusTotal report</i> link. It is not part of {@code --check}, the release process, CI
 * or the plug-in, and the key never leaves the machine it runs on.
 *
 * <p>{@code GITHUB_TOKEN}, if set, is sent to GitHub's API (a higher rate limit, as in CI).
 */
public final class UpdateNeovimDownloads {

    /** The data file, relative to the repository root (the working folder when run as documented). */
    static final Path FILE = Path.of("plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/src/io/github/"
            + "jm6cnddxbwftaks/neovim4eclipse/util/neovim-downloads.properties");

    /** The six platform codes, in the file's order: OS letter (l, m, w) then processor (x, a). */
    static final List<String> CODES = List.of("lx", "la", "mx", "ma", "wx", "wa");

    static final String URL_TEMPLATE = "https://github.com/neovim/neovim/releases/download/v{version}/{asset}";

    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    /** How old a release must be before a rewrite pins it (see the class comment, "What the hashes ..."). */
    static final Duration MIN_AGE = Duration.ofDays(7);

    static final String ALLOW_YOUNG = "--allow-young";

    /**
     * {@code --keep <folder>}: a rewrite also writes each archive it downloads there, as
     * {@code <folder>/<asset>}, for {@code releng/tools/neovim/NeovimUpgrade.java} to unpack next.
     * Without it nothing is written: the bytes only pass through the hashes.
     */
    static final String KEEP = "--keep";

    /** The folder of {@link #KEEP}, or {@code null}. */
    private static Path keep;

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30)).build();

    /** One archive: what the file says, or what was measured. */
    /**
     * One archive: what the file says, or what was measured. {@code tree} is the tree hash of the
     * files inside it (see {@link #treeHash}); {@code null} for GitHub's API entry, which knows none.
     */
    record Entry(String code, String asset, long size, String sha256, String tree) {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("install")) {
            try {
                System.out.println(install(load(), args[1], Path.of(args[2])));
            } catch (Failure f) {
                System.err.println("FAILED: " + f.getMessage());
                System.exit(1);
            }
            return;
        }
        if (args.length == 0) {
            usage();
        }
        boolean allowYoung = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals(ALLOW_YOUNG)) {
                allowYoung = true;
            } else if (args[i].equals(KEEP) && i + 1 < args.length) {
                keep = Path.of(args[++i]);
            } else {
                usage();
            }
        }
        if (args.length > 1 && !VERSION.matcher(args[0]).matches()) {
            usage(); // the flags belong to a rewrite only
        }
        Properties current = load();
        String arg = args[0];
        try {
            switch (arg) {
                case "--check" -> check(current);
                case "--virustotal" -> virusTotal(current);
                default -> {
                    if (!VERSION.matcher(arg).matches()) {
                        usage();
                    }
                    rewrite(current, arg, allowYoung);
                }
            }
        } catch (Failure f) {
            System.err.println("FAILED: " + f.getMessage());
            System.exit(1);
        }
    }

    private static void usage() {
        System.err.println("Usage: java releng/UpdateNeovimDownloads.java <version> [" + ALLOW_YOUNG + "] [" + KEEP
                + " <folder>] | --check | --virustotal | install <code|auto> <folder>");
        System.exit(2);
    }

    /** Downloads and compares every archive of {@code version}, then writes the file. */
    static void rewrite(Properties current, String version, boolean allowYoung) throws Exception {
        String api = release(version);
        Instant published = publishedAt(api);
        if (!allowYoung) {
            requireOldEnough(version, published, Instant.now());
        }
        Map<String, Entry> measured = measure(current, version, api);
        String text = render(version, measured);
        Files.writeString(FILE, text, StandardCharsets.UTF_8);
        System.out.println("Wrote " + FILE + " for Neovim " + version + " (published " + published + ").");
        System.out.println("Look at each archive's VirusTotal page before committing (or run --virustotal):");
        for (Entry e : measured.values()) {
            System.out.println("  " + e.asset() + ": https://www.virustotal.com/gui/file/" + e.sha256());
        }
        System.out.println("NeovimVersion.MINIMUM is now " + version + " (it is read from this file). The copies of the"
                + " number in releng/version-copies.txt, the changelog and the tests are the rest of"
                + " java releng/tools/neovim/NeovimUpgrade.java " + version + ".");
    }

    /** The version in the file, downloaded and compared again; fails on any difference. */
    static void check(Properties current) throws Exception {
        String version = required(current, "version");
        Map<String, Entry> measured = measure(current, version, release(version));
        List<String> wrong = new ArrayList<>();
        for (Entry e : measured.values()) {
            String size = required(current, e.code() + ".size");
            String sha = required(current, e.code() + ".sha256");
            String tree = required(current, e.code() + ".tree");
            if (!size.equals(String.valueOf(e.size())) || !sha.equalsIgnoreCase(e.sha256())) {
                wrong.add(e.asset() + ": the file says " + size + " bytes, " + sha + "; GitHub has " + e.size()
                        + " bytes, " + e.sha256());
            } else if (!tree.equalsIgnoreCase(e.tree())) {
                wrong.add(e.asset() + ": the file says tree " + tree + ", the archive's files give " + e.tree());
            }
        }
        if (!wrong.isEmpty()) {
            throw new Failure(String.join("\n", wrong));
        }
        if (!URL_TEMPLATE.equals(current.getProperty("url"))) {
            throw new Failure("url is " + current.getProperty("url") + ", expected " + URL_TEMPLATE);
        }
        System.out.println("OK: all six archives of Neovim " + version + " match " + FILE + ".");
    }

    /** The release's entry in GitHub's API, as JSON text. */
    static String release(String version) throws Exception {
        return get("https://api.github.com/repos/neovim/neovim/releases/tags/v" + version, true);
    }

    /**
     * When the release was published: its {@code "published_at"} (assets have no such field, so the
     * first one is the release's own). Example: {@code "published_at":"2026-10-20T14:03:11Z"}.
     */
    static Instant publishedAt(String json) {
        Matcher m = Pattern.compile("\"published_at\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (!m.find()) {
            throw new Failure("GitHub's release has no published_at (a draft?)");
        }
        return Instant.parse(m.group(1));
    }

    /** Fails if {@code published} is less than {@link #MIN_AGE} before {@code now}. */
    static void requireOldEnough(String version, Instant published, Instant now) {
        Instant ready = published.plus(MIN_AGE);
        if (now.isBefore(ready)) {
            throw new Failure("Neovim " + version + " was published " + published + ", less than "
                    + MIN_AGE.toDays() + " days ago. Pin it from " + ready + " on, once a replaced archive would "
                    + "likely have been noticed; or add " + ALLOW_YOUNG + " (for example for a security fix).");
        }
    }

    /**
     * For each code: download the archive, hash it, and compare with GitHub's API entry.
     *
     * @param api the release's API answer ({@link #release})
     * @return the measured entries by code, in {@link #CODES} order
     */
    static Map<String, Entry> measure(Properties current, String version, String api) throws Exception {
        Map<String, Entry> result = new LinkedHashMap<>();
        for (String code : CODES) {
            String asset = required(current, code + ".asset");
            String url = URL_TEMPLATE.replace("{version}", version).replace("{asset}", asset);
            System.out.println("Downloading " + url);
            Entry downloaded = download(code, asset, url);
            Entry github = fromApi(api, code, asset);
            if (downloaded.size() != github.size() || !downloaded.sha256().equalsIgnoreCase(github.sha256())) {
                throw new Failure(asset + ": the download (" + downloaded.size() + " bytes, " + downloaded.sha256()
                        + ") differs from GitHub's published digest (" + github.size() + " bytes, " + github.sha256()
                        + ")");
            }
            System.out.println("  " + downloaded.size() + " bytes, sha256 " + downloaded.sha256() + " (matches GitHub)");
            result.put(code, downloaded);
        }
        return result;
    }

    /** Streams {@code url} through SHA-256, keeping a copy in {@link #keep}'s folder if one is set. */
    static Entry download(String code, String asset, String url) throws Exception {
        HttpResponse<InputStream> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new Failure(url + ": HTTP " + response.statusCode()
                    + (response.statusCode() == 404 ? " (was the asset renamed? fix " + code + ".asset by hand)" : ""));
        }
        if (keep == null) {
            return measured(code, asset, response.body());
        }
        // A copy on its way through: written as <asset>.part and renamed once whole, so a file
        // named <asset> is never a cut-off download. Its hash is the one measured here.
        Files.createDirectories(keep);
        Path part = keep.resolve(asset + ".part");
        Entry entry;
        try (OutputStream copy = Files.newOutputStream(part); InputStream body = response.body()) {
            entry = measured(code, asset, new FilterInputStream(body) {
                @Override
                public int read() throws IOException {
                    int b = super.read();
                    if (b >= 0) {
                        copy.write(b);
                    }
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = super.read(b, off, len);
                    if (n > 0) {
                        copy.write(b, off, n);
                    }
                    return n;
                }
            });
        }
        Files.move(part, keep.resolve(asset), StandardCopyOption.REPLACE_EXISTING);
        return entry;
    }

    /** The size, SHA-256 and tree hash of the archive {@code body} holds, read once to its end. */
    private static Entry measured(String code, String asset, InputStream body) throws Exception {
        MessageDigest sha = sha256();
        long[] size = { 0 };
        String tree;
        // One pass: the bytes go through the archive's SHA-256 (and size) on their way to the reader
        // of its entries, so nothing is written to disk.
        try (InputStream in = new FilterInputStream(body) {
            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0) {
                    sha.update((byte) b);
                    size[0]++;
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0) {
                    sha.update(b, off, n);
                    size[0] += n;
                }
                return n;
            }
        }) {
            tree = treeHash(asset.endsWith(".zip") ? zipFiles(in) : tarFiles(in));
            in.transferTo(OutputStream.nullOutputStream()); // the rest (padding), for the whole SHA-256
        }
        return new Entry(code, asset, size[0], HexFormat.of().formatHex(sha.digest()), tree);
    }

    // ---- install: the pinned Neovim for one platform, checked, unpacked ---------------------------

    /** How often a download is tried before the install fails, waiting 5, 10, then 20 s between tries. */
    static final int DOWNLOAD_ATTEMPTS = 4;

    /**
     * The platform code of the machine this runs on: {@code lx} for Linux on x86_64, {@code ma} for
     * macOS on arm64. The same letters as the data file (and the plug-in's {@code NeovimPlatform}).
     */
    static String codeHere() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String osLetter = os.startsWith("linux") ? "l" : os.startsWith("mac") ? "m" : os.startsWith("windows") ? "w" : null;
        String archLetter = arch.equals("amd64") || arch.equals("x86_64") ? "x"
                : arch.equals("aarch64") || arch.equals("arm64") ? "a" : null;
        if (osLetter == null || archLetter == null) {
            throw new Failure("no Neovim release for " + os + " on " + arch);
        }
        return osLetter + archLetter;
    }

    /**
     * {@code install <code> <folder>} (see the class comment): the program's path,
     * {@code <folder>/<archive's top folder>/bin/nvim} ({@code nvim.exe} on Windows).
     */
    static Path install(Properties current, String code, Path folder) throws Exception {
        if (code.equals("auto")) {
            code = codeHere();
        }
        if (!CODES.contains(code)) {
            throw new Failure("unknown platform code " + code + " (one of " + CODES + ", or auto)");
        }
        String version = required(current, "version");
        String asset = required(current, code + ".asset");
        long size = Long.parseLong(required(current, code + ".size"));
        String sha = required(current, code + ".sha256");
        String tree = required(current, code + ".tree");
        String url = URL_TEMPLATE.replace("{version}", version).replace("{asset}", asset);
        boolean zip = asset.endsWith(".zip");
        String top = asset.substring(0, asset.length() - (zip ? ".zip".length() : ".tar.gz".length()));
        Files.createDirectories(folder);
        Path home = folder.resolve(top);
        Path program = home.resolve("bin").resolve(code.startsWith("w") ? "nvim.exe" : "nvim");
        System.err.println("Neovim " + version + " for " + code + ": " + url);
        // Already there from an earlier run (LikeCi installs into the same target/like-ci each time):
        // kept when its files have the pinned tree hash, so a local run needs no download. A CI runner
        // starts empty, so CI always downloads, and checks what GitHub serves now against the pin.
        if (treeHashOrNull(home) instanceof String there && there.equals(tree)) {
            System.err.println("  already in " + home + ", tree " + tree + " (matches)");
            System.err.println("  " + firstLine(program));
            return program.toAbsolutePath();
        }
        Path archive = folder.resolve(asset);
        String downloaded = downloadWithRetries(url, archive);
        long actualSize = Files.size(archive);
        if (actualSize != size || !downloaded.equalsIgnoreCase(sha)) {
            throw new Failure(asset + " does not match " + FILE + ": expected " + size + " bytes, sha256 " + sha
                    + "; got " + actualSize + " bytes, sha256 " + downloaded);
        }
        System.err.println("  " + size + " bytes, sha256 " + sha + " (matches)");
        String inArchive;
        try (InputStream in = Files.newInputStream(archive)) {
            inArchive = treeHash(zip ? zipFiles(in) : tarFiles(in));
        }
        if (!inArchive.equals(tree)) {
            throw new Failure("the files in " + asset + " have tree hash " + inArchive + ", not the pinned " + tree);
        }
        // Unpacked into a folder of its own first, then moved into place: a copy left from an older
        // release (a file the new one dropped, a running nvim.exe on Windows) is replaced whole, never
        // mixed with the new files, and an entry outside the archive's top folder (an absolute name,
        // another top folder) shows as a second entry in that folder and fails, instead of landing
        // beside it unchecked.
        Path staging = folder.resolve(top + ".unpacking");
        deleteTree(staging);
        Files.createDirectories(staging);
        if (zip) {
            unzip(archive, staging);
        } else {
            untar(archive, staging);
        }
        List<String> unpackedNames;
        try (var list = Files.list(staging)) {
            unpackedNames = list.map(f -> f.getFileName().toString()).sorted().toList();
        }
        if (!unpackedNames.equals(List.of(top))) {
            throw new Failure(asset + " unpacked to " + unpackedNames + ", not the one folder " + top);
        }
        String unpacked = treeHash(filesIn(staging.resolve(top)));
        if (!unpacked.equals(tree)) {
            throw new Failure("the unpacked files of " + asset + " have tree hash " + unpacked + ", not the pinned "
                    + tree + " (a full disk, or a file changed meanwhile?)");
        }
        deleteTree(home);
        Files.move(staging.resolve(top), home);
        deleteTree(staging);
        Files.delete(archive);
        System.err.println("  tree " + tree + " (matches, in the archive and unpacked)");
        System.err.println("  " + firstLine(program));
        return program.toAbsolutePath();
    }

    /** The tree hash of the files in {@code home}, or null when there is no such folder or it holds a link. */
    private static String treeHashOrNull(Path home) throws IOException {
        if (!Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            return treeHash(filesIn(home));
        } catch (Failure linkInside) {
            return null;
        }
    }

    /**
     * Deletes {@code root} and everything below it, if it is there. Links are deleted, never
     * followed ({@link Files#walkFileTree} does not follow them), so nothing outside is touched.
     */
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
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
    }

    /** Downloads {@code url} into {@code file}, tried {@link #DOWNLOAD_ATTEMPTS} times; its SHA-256. */
    static String downloadWithRetries(String url, Path file) throws Exception {
        long wait = 5;
        for (int attempt = 1;; attempt++) {
            try {
                return downloadTo(url, file);
            } catch (IOException e) {
                if (attempt == DOWNLOAD_ATTEMPTS) {
                    throw new Failure(url + ": " + e.getMessage() + " (" + attempt + " tries)");
                }
                System.err.println("  try " + attempt + " failed (" + e.getMessage() + "); again in " + wait + " s");
                Thread.sleep(wait * 1000);
                wait *= 2;
            }
        }
    }

    /** One download of {@code url} into {@code file}, hashing what is written; an HTTP error is an IOException (retried), a 404 a Failure (not). */
    private static String downloadTo(String url, Path file) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 404) {
            response.body().close();
            throw new Failure(url + ": HTTP 404 (was the asset renamed?)");
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode());
        }
        MessageDigest sha = sha256();
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0;) {
                sha.update(buffer, 0, n);
                out.write(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    /** Unpacks a zip archive (Windows) into {@code folder}, refusing entries outside it. */
    private static void unzip(Path archive, Path folder) throws IOException {
        Path root = folder.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive), StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new Failure("an archive entry outside its folder: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Unpacks a gzip-compressed tar archive (Linux, macOS) with {@code tar}, which keeps the execute bits. */
    private static void untar(Path archive, Path folder) throws Exception {
        Process tar = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", folder.toString()).inheritIO()
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (tar.waitFor() != 0) {
            throw new Failure("tar -xzf " + archive + " failed (exit " + tar.exitValue() + ")");
        }
    }

    /**
     * The files below {@code home}, each by its path relative to it with {@code /} between names, to
     * its SHA-256, by the same rule as an archive's entries ({@link #addFile}). A link fails, as the
     * plug-in counts an install with one as none.
     */
    static Map<String, String> filesIn(Path home) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (var walk = Files.walk(home)) {
            for (Path file : (Iterable<Path>) walk::iterator) {
                if (Files.isSymbolicLink(file)) {
                    throw new Failure("a link in the unpacked Neovim: " + file);
                }
                if (Files.isRegularFile(file)) {
                    StringBuilder name = new StringBuilder("top");
                    for (Path part : home.relativize(file)) {
                        name.append('/').append(part);
                    }
                    try (InputStream in = Files.newInputStream(file)) {
                        addFile(files, name.toString(), in);
                    }
                }
            }
        }
        return files;
    }

    /** The first line {@code program --version} prints, as a check that it runs here. */
    private static String firstLine(Path program) throws Exception {
        Process p = new ProcessBuilder(program.toString(), "--version").redirectErrorStream(true).start();
        p.getOutputStream().close();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new Failure(program + " --version failed: " + output.strip());
        }
        return output.lines().findFirst().orElse("");
    }

    /**
     * The asset's size and digest in the release's API answer. Read with plain text search, as the
     * program has no JSON library: after {@code "name":"<asset>"} come that asset's own
     * {@code "size"} and {@code "digest":"sha256:…"} (GitHub has published digests since 2025).
     */
    static Entry fromApi(String json, String code, String asset) {
        // Spaces around the colons are allowed (\\s*): GitHub sends compact JSON today, but a
        // pretty-printed answer is the same JSON, and should not read as "no digest".
        Matcher name = Pattern.compile("\"name\"\\s*:\\s*\"" + Pattern.quote(asset) + "\"").matcher(json);
        if (!name.find()) {
            throw new Failure("GitHub's release has no asset named " + asset);
        }
        int at = name.start();
        // Only this asset's own fields: up to the next asset's "name", or the end. Without the bound,
        // an asset with no digest would take the next asset's, and fail as a false mismatch.
        Matcher nextName = Pattern.compile("\"name\"\\s*:\\s*\"").matcher(json);
        int end = nextName.find(name.end()) ? nextName.start() : json.length();
        Matcher size = Pattern.compile("\"size\"\\s*:\\s*(\\d+)").matcher(json).region(at, end);
        Matcher digest = Pattern.compile("\"digest\"\\s*:\\s*\"sha256:([0-9a-f]{64})\"").matcher(json).region(at, end);
        if (!size.find() || !digest.find()) {
            throw new Failure("GitHub's API gives no size or sha256 digest for " + asset);
        }
        return new Entry(code, asset, Long.parseLong(size.group(1)), digest.group(1), null);
    }

    /** The file's text: a fixed header, then the release and the six entries. */
    static String render(String version, Map<String, Entry> entries) {
        StringBuilder s = new StringBuilder();
        s.append("""
                # SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
                # SPDX-License-Identifier: EPL-2.0
                #
                # The Neovim that "Get Neovim" installs, and how to check it (util/NeovimRelease reads this).
                # Do not edit by hand: run
                #   java releng/UpdateNeovimDownloads.java <version>
                # which downloads each archive, hashes it, compares with GitHub's published digest and
                # rewrites this file (docs/UPGRADING.md, "Neovim"). The one hand edit is an asset name that
                # Neovim renamed. Codes: OS letter (l Linux, m macOS, w Windows), then processor (x x86_64,
                # a arm64), as in the plug-in's folder neovim/<code>/ (util/NeovimPlatform). <code>.tree is
                # the hash of every file inside the archive (util/InstallTree), checked at each start.
                """);
        s.append("version=").append(version).append('\n');
        s.append("url=").append(URL_TEMPLATE).append('\n');
        for (Entry e : entries.values()) {
            s.append('\n');
            s.append(e.code()).append(".asset=").append(e.asset()).append('\n');
            s.append(e.code()).append(".size=").append(e.size()).append('\n');
            s.append(e.code()).append(".sha256=").append(e.sha256()).append('\n');
            s.append(e.code()).append(".tree=").append(e.tree()).append('\n');
        }
        return s.toString();
    }

    /** Asks VirusTotal about each hash in the file; prints, writes nothing. */
    static void virusTotal(Properties current) throws Exception {
        String key = System.getenv("VT_API_KEY");
        if (key == null || key.isBlank()) {
            throw new Failure("VT_API_KEY is not set (a personal VirusTotal API key; it never leaves this machine)");
        }
        boolean first = true;
        for (String code : CODES) {
            if (!first) {
                Thread.sleep(15_000); // the free key allows 4 requests a minute
            }
            first = false;
            String asset = required(current, code + ".asset");
            String sha = required(current, code + ".sha256");
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(
                    "https://www.virustotal.com/api/v3/files/" + sha)).header("x-apikey", key)
                    .timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 404) {
                System.out.println(asset + ": unknown to VirusTotal. Upload it at https://www.virustotal.com/gui/home/upload"
                        + " so its report exists.");
            } else if (r.statusCode() == 200) {
                System.out.println(asset + ": " + stats(r.body()) + "  https://www.virustotal.com/gui/file/" + sha);
            } else {
                System.out.println(asset + ": VirusTotal answered HTTP " + r.statusCode());
            }
        }
    }

    /** {@code malicious 0, suspicious 0, harmless 0, undetected 62} from the analysis stats. */
    static String stats(String json) {
        int at = json.indexOf("\"last_analysis_stats\"");
        if (at < 0) {
            return "no analysis yet";
        }
        List<String> parts = new ArrayList<>();
        for (String name : List.of("malicious", "suspicious", "harmless", "undetected")) {
            Matcher m = Pattern.compile("\"" + name + "\":\\s*(\\d+)").matcher(json);
            if (m.find(at)) {
                parts.add(name + " " + m.group(1));
            }
        }
        return String.join(", ", parts);
    }

    static String get(String url, boolean github) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("Accept", "application/vnd.github+json");
        String token = System.getenv("GITHUB_TOKEN");
        if (github && token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new Failure(url + ": HTTP " + r.statusCode());
        }
        return r.body();
    }

    // ---- The tree hash: the same rule as the plug-in's util/InstallTree --------------------------

    /**
     * The tree hash of an archive's files, each path below the archive's top folder
     * ({@code nvim-linux-arm64/bin/nvim} gives {@code bin/nvim}) to its SHA-256: one line
     * {@code <sha256><two spaces><path>} per file, in the order of the paths' UTF-8 bytes, and the
     * SHA-256 of those lines. Files the operating system adds ({@code .DS_Store}, {@code Thumbs.db},
     * {@code desktop.ini}, {@code ._*}) are left out, as the plug-in leaves them out of an install.
     */
    static String treeHash(Map<String, String> files) {
        List<String> paths = new ArrayList<>(files.keySet());
        paths.sort((a, b) -> Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)));
        StringBuilder lines = new StringBuilder();
        for (String path : paths) {
            lines.append(files.get(path)).append("  ").append(path).append('\n');
        }
        return HexFormat.of().formatHex(sha256().digest(lines.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** Adds one archive entry to {@code files}: its path below the top folder, and its SHA-256. */
    private static void addFile(Map<String, String> files, String name, InputStream data) throws IOException {
        String clean = name.replace('\\', '/');
        while (clean.startsWith("./")) {
            clean = clean.substring(2);
        }
        int slash = clean.indexOf('/');
        String path = slash < 0 ? "" : clean.substring(slash + 1);
        String last = path.substring(path.lastIndexOf('/') + 1);
        // The same rule as the plug-in's PluginNeovim.isSystemFile, which this standalone script cannot
        // import: case ignored, except for "._". A thumbs.db hashed here but skipped there would make
        // every install of the release fail its start check.
        String lower = last.toLowerCase(Locale.ROOT);
        if (path.isEmpty() || path.contains("..") || lower.equals(".ds_store") || lower.equals("thumbs.db")
                || lower.equals("desktop.ini") || last.startsWith("._")) {
            if (path.isEmpty() || path.contains("..")) {
                throw new Failure("an archive entry outside its top folder: " + name);
            }
            return;
        }
        MessageDigest sha = sha256();
        byte[] buffer = new byte[64 * 1024];
        for (int n; (n = data.read(buffer)) > 0;) {
            sha.update(buffer, 0, n);
        }
        if (files.put(path, HexFormat.of().formatHex(sha.digest())) != null) {
            throw new Failure("an archive entry twice: " + name);
        }
    }

    /** The files of a zip archive (Windows). */
    static Map<String, String> zipFiles(InputStream in) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8); // not closed: the caller drains `in`
        for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
            if (!entry.isDirectory()) {
                addFile(files, entry.getName(), zip);
            }
        }
        return files;
    }

    /**
     * The files of a gzip-compressed tar archive (Linux, macOS): the same few header types the
     * plug-in's {@code util/ArchiveExtractor} reads (plain files, folders, PAX and GNU long names);
     * a link fails, as the plug-in would refuse to unpack it.
     */
    static Map<String, String> tarFiles(InputStream in) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        InputStream tar = new GZIPInputStream(in, 64 * 1024); // not closed: the caller drains `in`
        byte[] header = new byte[512];
        String longName = null;
        while (tar.readNBytes(header, 0, 512) == 512 && !allZero(header)) {
            String name = text(header, 0, 100);
            String prefix = text(header, 345, 155);
            if (!prefix.isEmpty() && "ustar".equals(text(header, 257, 5))) {
                name = prefix + "/" + name;
            }
            long size = octal(header, 124, 12);
            char type = (char) header[156];
            long padding = (512 - size % 512) % 512;
            // A GNU "L" and a PAX "x" header may both describe the next entry, one after the other:
            // the name waits for the entry itself, so neither takes the other's (a PAX path wins), as
            // in the plug-in's util/ArchiveExtractor.
            if (type == 'x' || type == 'L') {
                String given = type == 'x' ? paxPath(tar.readNBytes((int) size))
                        : text(tar.readNBytes((int) size), 0, (int) size);
                longName = given != null ? given : longName;
                tar.skipNBytes(padding);
                continue;
            }
            if (longName != null && type != 'g') {
                name = longName;
                longName = null;
            }
            switch (type) {
                case '0', '\0', '7' -> addFile(files, name, new ByteArrayInputStream(tar.readNBytes((int) size)));
                case '1', '2' -> throw new Failure("the archive holds a link, which the plug-in refuses: " + name);
                default -> tar.skipNBytes(size); // folders, global headers
            }
            tar.skipNBytes(padding); // each entry's data fills whole 512-byte blocks
        }
        return files;
    }

    private static String paxPath(byte[] data) {
        String path = null;
        for (String record : new String(data, StandardCharsets.UTF_8).split("\n")) {
            int space = record.indexOf(' ');
            if (space > 0 && record.startsWith("path=", space + 1)) {
                path = record.substring(space + 6);
            }
        }
        return path;
    }

    private static boolean allZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String text(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && end < b.length && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] b, int off, int len) {
        long value = 0;
        for (int i = off; i < off + len; i++) {
            if (b[i] >= '0' && b[i] <= '7') {
                value = value * 8 + (b[i] - '0');
            }
        }
        return value;
    }

    static Properties load() throws IOException {
        Properties p = new Properties();
        if (!Files.isRegularFile(FILE)) {
            throw new IOException(FILE + " not found: run this from the repository's root folder");
        }
        try (var reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            p.load(reader);
        }
        return p;
    }

    static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new Failure(FILE + " has no " + key);
        }
        return value.strip();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A step failed: the message says which; nothing has been written. */
    static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Failure(String message) {
            super(message);
        }
    }
}
