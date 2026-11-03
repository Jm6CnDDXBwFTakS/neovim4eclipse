// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps {@code neovim-downloads.properties}, the data file behind the plug-in's <i>Get Neovim</i>
 * entry, in step with a Neovim release. A single-file Java program, so it runs the same on Linux,
 * macOS and Windows with only the JDK the build already needs (no {@code gh}, {@code jq} or Python).
 *
 * <h2>Usage</h2>
 * <pre>
 *   java releng/UpdateNeovimDownloads.java 0.13.0     rewrite the file for Neovim 0.13.0
 *   java releng/UpdateNeovimDownloads.java 0.13.0 --allow-young   the same for a release under 7 days old
 *   java releng/UpdateNeovimDownloads.java --check    verify the file as it is; writes nothing
 *   java releng/UpdateNeovimDownloads.java --virustotal   ask VirusTotal about each hash (needs VT_API_KEY)
 * </pre>
 *
 * <h2>What a rewrite does</h2>
 * <ol>
 *   <li>Takes the asset names from the current file. Neovim renames an asset now and then (0.10.4
 *       renamed {@code nvim-linux64} to {@code nvim-linux-x86_64}); that is the one hand edit, and
 *       the old name then fails here with a 404 that says so.</li>
 *   <li>Downloads each of the six archives and computes its size and SHA-256.</li>
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
 *   <li>Checking again: {@code --check} at every release, and CI's {@code install-neovim.sh} at
 *       every run, compare the files GitHub serves now with the pinned hashes, so a file replaced
 *       after pinning is caught.</li>
 * </ul>
 * If Neovim starts publishing signatures or attestations, verify them here instead.
 * After a rewrite, set {@code NeovimVersion.MINIMUM} to the same version and run the tests:
 * {@code VersionConsistencyTest} lists what else still names the old one (docs/UPGRADING.md).
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

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30)).build();

    /** One archive: what the file says, or what was measured. */
    record Entry(String code, String asset, long size, String sha256) {
    }

    public static void main(String[] args) throws Exception {
        boolean allowYoung = args.length == 2 && args[1].equals(ALLOW_YOUNG);
        if (args.length != 1 && !allowYoung) {
            usage();
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
        System.err.println("Usage: java releng/UpdateNeovimDownloads.java <version> [" + ALLOW_YOUNG
                + "] | --check | --virustotal");
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
        System.out.println("Next: set NeovimVersion.MINIMUM to " + version
                + " and run the tests; VersionConsistencyTest lists what else names the old version.");
    }

    /** The version in the file, downloaded and compared again; fails on any difference. */
    static void check(Properties current) throws Exception {
        String version = required(current, "version");
        Map<String, Entry> measured = measure(current, version, release(version));
        List<String> wrong = new ArrayList<>();
        for (Entry e : measured.values()) {
            String size = required(current, e.code() + ".size");
            String sha = required(current, e.code() + ".sha256");
            if (!size.equals(String.valueOf(e.size())) || !sha.equalsIgnoreCase(e.sha256())) {
                wrong.add(e.asset() + ": the file says " + size + " bytes, " + sha + "; GitHub has " + e.size()
                        + " bytes, " + e.sha256());
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
        Matcher m = Pattern.compile("\"published_at\":\"([^\"]+)\"").matcher(json);
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

    /** Streams {@code url} through SHA-256 without keeping it. */
    static Entry download(String code, String asset, String url) throws Exception {
        HttpResponse<InputStream> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new Failure(url + ": HTTP " + response.statusCode()
                    + (response.statusCode() == 404 ? " (was the asset renamed? fix " + code + ".asset by hand)" : ""));
        }
        MessageDigest sha = sha256();
        long size = 0;
        try (InputStream in = response.body()) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0;) {
                sha.update(buffer, 0, n);
                size += n;
            }
        }
        return new Entry(code, asset, size, HexFormat.of().formatHex(sha.digest()));
    }

    /**
     * The asset's size and digest in the release's API answer. Read with plain text search, as the
     * program has no JSON library: after {@code "name":"<asset>"} come that asset's own
     * {@code "size"} and {@code "digest":"sha256:…"} (GitHub has published digests since 2025).
     */
    static Entry fromApi(String json, String code, String asset) {
        int at = json.indexOf("\"name\":\"" + asset + "\"");
        if (at < 0) {
            throw new Failure("GitHub's release has no asset named " + asset);
        }
        // Only this asset's own fields: up to the next asset's "name", or the end. Without the bound,
        // an asset with no digest would take the next asset's, and fail as a false mismatch.
        int next = json.indexOf("\"name\":\"", at + 1);
        int end = next < 0 ? json.length() : next;
        Matcher size = Pattern.compile("\"size\":(\\d+)").matcher(json).region(at, end);
        Matcher digest = Pattern.compile("\"digest\":\"sha256:([0-9a-f]{64})\"").matcher(json).region(at, end);
        if (!size.find() || !digest.find()) {
            throw new Failure("GitHub's API gives no size or sha256 digest for " + asset);
        }
        return new Entry(code, asset, Long.parseLong(size.group(1)), digest.group(1));
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
                # a arm64), as in the plug-in's folder neovim/<code>/ (util/NeovimPlatform).
                """);
        s.append("version=").append(version).append('\n');
        s.append("url=").append(URL_TEMPLATE).append('\n');
        for (Entry e : entries.values()) {
            s.append('\n');
            s.append(e.code()).append(".asset=").append(e.asset()).append('\n');
            s.append(e.code()).append(".size=").append(e.size()).append('\n');
            s.append(e.code()).append(".sha256=").append(e.sha256()).append('\n');
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
