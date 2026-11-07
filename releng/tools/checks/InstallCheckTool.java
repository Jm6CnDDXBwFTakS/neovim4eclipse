// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import common.Ask;
import common.EclipseCopy;
import common.Folders;
import common.PgpKey;
import common.Settings;
import common.ToolFailure;
import common.Version;

/**
 * Installs a release from the public update site into a fresh copy of your Eclipse, the way a user
 * installs it, and then offers to start that Eclipse for a look:
 * <pre>
 *   java releng/tools/checks/InstallCheck.java          # the newest release on the site
 *   java releng/tools/checks/InstallCheck.java 0.2.4    # a given one
 * </pre>
 * The Release tool's {@code tag} step already checks the release's files (the ZIP, both kinds of
 * signature, the site's listing). This checks what those cannot: that p2 resolves the feature
 * into a real Eclipse from the live site, and trusts what it downloads. It is not a release step,
 * since it is worth running at any time: after an Eclipse upgrade, it shows the released plug-in
 * still installs into the new Eclipse. {@code tag} offers it, and the Release tool's status shows
 * whether it passed for the release.
 *
 * <h2>Trusted as strictly as a careful user</h2>
 * p2's director (<i>Install New Software</i> without a window) takes the trust decisions Eclipse
 * otherwise asks about in its Trust dialog as arguments. This run trusts only:
 * <ul>
 *   <li>{@code -trustedAuthorities https://jm6cnddxbwftaks.github.io/}: content from the update
 *       site's host, nothing else (the trailing {@code /} is needed: without it the director
 *       trusts nothing);</li>
 *   <li>{@code -trustedPGPKeys <fingerprint>}: artifacts signed by the release key, its fingerprint
 *       computed from {@code releng/signing-key.asc} ({@code common/PgpKey}) in lower case, which is
 *       how the director compares it (upper case, or the 16-digit key id, trusts nothing);</li>
 *   <li>{@code -trustSignedContentOnly}: no unsigned artifact.</li>
 * </ul>
 * So an artifact signed by another key, or not signed, fails the install, where a user could click
 * <i>Trust</i> on it. {@code -verboseTrust} prints which key signed what.
 *
 * <h2>A fresh copy of your Eclipse, each time</h2>
 * The Eclipse is a copy of yours under {@code ~/var/n4e-checks/install-check/eclipse}, made with
 * hard links as {@code ManualChecks launch} makes its copy (both use {@code common/EclipseCopy}),
 * and made anew each run, so nothing of an earlier run is left. If your Eclipse has the plug-in
 * installed, the copy has it too; the install then replaces it in the same step, as an update
 * would. The plug-in's Neovim folder ({@code XDG_DATA_HOME}) and the workspace are new and empty
 * too, so the Eclipse it starts shows <i>Get Neovim</i> as a first-time user sees it. A separate
 * folder from {@code ManualChecks}' copy, which holds the build under test.
 *
 * <p>When the install passed, it records the version in {@code target/release-tool/install-checked}
 * ({@link #RECORD}), which the Release tool's status reads.
 */
public final class InstallCheckTool {

    static final String TOOL = "releng/tools/checks/InstallCheck.java";

    /** Where a passed install is recorded, from the repository's root: the version and the time. */
    public static final String RECORD = "target/release-tool/install-checked";

    /** The p2 director's application, run inside the Eclipse copy. */
    private static final List<String> DIRECTOR = List.of("--launcher.suppressErrors", "-nosplash", "-application",
            "org.eclipse.equinox.p2.director");

    private InstallCheckTool() {
    }

    /** What the command line asked for. */
    record Options(Optional<Version> version, Path root, String eclipse, Ask.Mode mode, boolean launch) {

        static Options parse(String[] args) {
            Optional<Version> version = Optional.empty();
            Path root = Path.of(System.getProperty("user.home"), "var", "n4e-checks", "install-check");
            String eclipse = System.getenv("ECLIPSE");
            Ask.Mode mode = Ask.Mode.ASK;
            boolean launch = true;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--root" -> root = Path.of(value(args, ++i));
                    case "--eclipse" -> eclipse = value(args, ++i);
                    case "--yes" -> mode = Ask.Mode.YES;
                    case "--no-launch" -> launch = false;
                    case "-h", "--help" -> throw new ToolFailure(usage());
                    default -> {
                        if (args[i].startsWith("-") || version.isPresent()) {
                            throw new ToolFailure("unknown argument " + args[i] + "\n" + usage());
                        }
                        version = Optional.of(Version.parse(args[i])); // stops on anything but 0.2.4's form
                    }
                }
            }
            return new Options(version, root.toAbsolutePath(), eclipse, mode, launch);
        }

        private static String value(String[] args, int i) {
            if (i >= args.length) {
                throw new ToolFailure(args[i - 1] + " needs a value");
            }
            return args[i];
        }
    }

    static String usage() {
        return """
                Usage: java releng/tools/checks/InstallCheck.java [<version>] [--eclipse <program>] [--root <folder>] [--no-launch] [--yes]
                  <version>             the release to install, such as 0.2.4; default the newest on the update site
                  --eclipse <program>   the Eclipse to copy (default as ManualChecks: $ECLIPSE, else eclipse on PATH,
                                        /Applications/Eclipse.app/Contents/MacOS/eclipse or C:\\eclipse\\eclipse.exe)
                  --root <folder>       where the copy, its workspace and Neovim folder go (default ~/var/n4e-checks/install-check)
                  --no-launch           only install and check; do not offer to start the Eclipse
                  --yes                 start it without asking""";
    }

    public static void main(String[] args) {
        try {
            Path repo = Path.of("").toAbsolutePath();
            if (!Files.isRegularFile(repo.resolve(TOOL))) {
                throw new ToolFailure("run this from the repository's root folder: java " + TOOL);
            }
            run(repo, Options.parse(args));
        } catch (ToolFailure f) {
            System.err.println();
            System.err.println("InstallCheck: " + f.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println();
            System.err.println("InstallCheck: " + e);
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(1);
        }
    }

    private static void run(Path repo, Options options) throws IOException, InterruptedException {
        Settings settings = Settings.load(List.of(repo.resolve(Settings.COMMON)), Map.of());
        String site = settings.get("site.url");
        String fingerprint = PgpKey.fingerprint(Files.readString(repo.resolve(settings.get("signing.key"))));

        // The release: asked, or the site's newest; either way it must be on the site.
        String listing = fetch(site + "compositeContent.xml");
        List<Version> released = releases(listing);
        Version v = options.version().orElseGet(() -> released.stream().max(Version::compareTo)
                .orElseThrow(() -> new ToolFailure(site + " lists no release")));
        if (!released.contains(v)) {
            throw new ToolFailure(site + " has no release " + v + " (it has " + released.stream().map(Version::toString).toList() + ")");
        }
        System.out.println("Release " + v + " from " + site + ", signed by key " + PgpKey.grouped(fingerprint));

        // A fresh copy of your Eclipse, with an empty workspace and Neovim folder.
        Path root = options.root();
        Path workspace = root.resolve("workspace");
        if (EclipseCopy.inUse(workspace)) {
            throw new ToolFailure("an Eclipse from an earlier InstallCheck still runs in " + workspace + "; close it, then run this again");
        }
        Path yours = EclipseCopy.program(options.eclipse() != null ? options.eclipse() : EclipseCopy.defaultEclipse())
                .toRealPath();
        Path source = EclipseCopy.installation(yours);
        if (!EclipseCopy.isEclipse(source)) {
            throw new ToolFailure(source + " (the folder of " + yours + ") is not an Eclipse installation: no eclipse.ini and plugins/"
                    + " there. Name the eclipse program itself with --eclipse or the ECLIPSE environment variable");
        }
        try {
            Folders.deleteTree(root); // links and copies only: your Eclipse keeps its files
        } catch (ToolFailure f) {
            throw new ToolFailure(f.getMessage() + ". Close whatever still uses a file there (an Eclipse or Neovim from an earlier"
                    + " run), then run this again");
        }
        Path copy = root.resolve("eclipse").resolve(source.getFileName().toString());
        System.out.println("Copying " + source + " to " + copy + " ...");
        long[] counts = EclipseCopy.copyTree(source, copy, EclipseCopy.inner(source));
        System.out.println("  " + counts[0] + " files linked, " + counts[1] + " copied");
        Path program = copy.resolve(source.relativize(yours).toString());
        Path director = EclipseCopy.director(program);
        Path log = root.resolve("director.log");

        // Install, replacing the plug-in if the copy has it from your Eclipse.
        boolean had = installedVersion(director, log).isPresent();
        List<String> install = new ArrayList<>(List.of(director.toString()));
        install.addAll(installArguments(site, v, fingerprint, had));
        System.out.println("Installing with p2's director" + (had ? ", replacing the plug-in the copy has from your Eclipse" : "")
                + " (output in " + log + "):");
        String output = EclipseCopy.run(install, log);
        directorLines(output).forEach(line -> System.out.println("  " + line));
        if (!output.contains("Operation completed")) {
            throw new ToolFailure("the install from " + site + " failed (see above; all of it in " + log + ")");
        }
        Optional<String> installed = installedVersion(director, log);
        if (installed.isEmpty() || !isVersion(installed.get(), v)) {
            throw new ToolFailure("after the install the copy has " + installed.map(s -> "version " + s).orElse("no plug-in")
                    + ", not " + v + " (" + log + ")");
        }
        System.out.println("ok: " + v + " installs from the update site, every artifact signed by the release key");
        record(repo, v);

        if (options.launch() && new Ask(options.mode(), "").offer("Start this Eclipse for a look (a new workspace and an empty"
                + " Neovim folder: Get Neovim opens at its first start)", "java " + TOOL + " " + v + " --yes")) {
            start(program, root, workspace);
        }
    }

    /** The feature's version in the copy, from the director's {@code -listInstalledRoots}. */
    private static Optional<String> installedVersion(Path director, Path log) throws IOException, InterruptedException {
        List<String> list = new ArrayList<>(List.of(director.toString()));
        list.addAll(DIRECTOR);
        list.addAll(List.of("-listInstalledRoots", "-data", "@none"));
        return EclipseCopy.installedVersion(EclipseCopy.run(list, log));
    }

    /**
     * The director's arguments that install release {@code v} from the site, after the program
     * (see the class comment for the trust arguments). Example, for 0.2.4 with the plug-in already
     * in the copy: {@code ... -repository https://jm6cnddxbwftaks.github.io/neovim4eclipse/releases/0.2.4/
     * -uninstallIU <feature> -installIU <feature> -trustSignedContentOnly -trustedPGPKeys a91f…063c
     * -trustedAuthorities https://jm6cnddxbwftaks.github.io/ -verboseTrust -data @none}.
     */
    static List<String> installArguments(String site, Version v, String fingerprint, boolean installed) {
        List<String> args = new ArrayList<>(DIRECTOR);
        args.addAll(List.of("-repository", site + "releases/" + v + "/"));
        if (installed) {
            args.addAll(List.of("-uninstallIU", EclipseCopy.FEATURE));
        }
        args.addAll(List.of("-installIU", EclipseCopy.FEATURE, "-trustSignedContentOnly",
                "-trustedPGPKeys", fingerprint.toLowerCase(java.util.Locale.ROOT), "-trustedAuthorities", authority(site),
                "-verboseTrust", "-data", "@none"));
        return args;
    }

    /** The site's scheme and host, with the {@code /} the director needs: {@code https://jm6cnddxbwftaks.github.io/}. */
    static String authority(String site) {
        URI uri = URI.create(site);
        return uri.getScheme() + "://" + uri.getAuthority() + "/";
    }

    private static final Pattern CHILD = Pattern.compile("<child location='releases/(\\d+\\.\\d+\\.\\d+)'/>");

    /** The releases a composite repository's {@code compositeContent.xml} lists, as {@code releases/0.2.4}. */
    static List<Version> releases(String compositeContent) {
        List<Version> found = new ArrayList<>();
        Matcher m = CHILD.matcher(compositeContent);
        while (m.find()) {
            found.add(Version.parse(m.group(1)));
        }
        return found;
    }

    /** Whether p2's version of the feature is release {@code v}: {@code 0.2.4}, or {@code 0.2.4.<qualifier>}. */
    static boolean isVersion(String p2Version, Version v) {
        return p2Version.equals(v.toString()) || p2Version.startsWith(v + ".");
    }

    /**
     * The director's output without the log lines of Eclipse's own bundles starting up (a date line
     * and an {@code INFO:} line each, dozens of them), leaving what it installed and trusted.
     */
    static List<String> directorLines(String output) {
        return output.lines().filter(l -> !l.isBlank() && !l.startsWith("INFO: ")
                && !l.matches("[A-Z][a-z]{2} \\d{1,2}, \\d{4} \\d{1,2}:\\d{2}:\\d{2} [AP]M .*")).toList();
    }

    /** The text at {@code url}, bypassing caches (a cache could hold the site from before the release). */
    private static String fetch(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "?t=" + System.currentTimeMillis()))
                .timeout(Duration.ofSeconds(60)).header("Cache-Control", "no-cache").build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new ToolFailure(url + " answered " + response.statusCode());
        }
        return response.body();
    }

    /** Records that {@code v} installed, for the Release tool's status: {@code 0.2.4 1794005281}. */
    private static void record(Path repo, Version v) {
        Path file = repo.resolve(RECORD);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, v + " " + Instant.now().getEpochSecond() + "\n");
        } catch (IOException e) {
            System.out.println("note: could not record the check in " + file + ": " + e.getMessage());
        }
    }

    /** The version a {@link #RECORD} names, if it is one: {@code 0.2.4 1794005281} gives 0.2.4. */
    public static Optional<Version> recorded(String text) {
        String[] parts = text.strip().split(" ");
        try {
            return parts.length == 2 ? Optional.of(Version.parse(parts[0])) : Optional.empty();
        } catch (ToolFailure e) {
            return Optional.empty();
        }
    }

    /** Starts the copy on a new workspace, with an empty Neovim folder; its output goes to a log. */
    private static void start(Path program, Path root, Path workspace) throws IOException {
        Path xdg = root.resolve("xdg");
        Files.createDirectories(xdg);
        Path log = root.resolve("eclipse.log");
        ProcessBuilder builder = new ProcessBuilder(program.toString(), "-data", workspace.toString()).directory(root.toFile());
        builder.environment().put("XDG_DATA_HOME", xdg.toString());
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        builder.start();
        System.out.println("Started " + program + " -data " + workspace + "\n  with XDG_DATA_HOME=" + xdg + ", output in " + log);
        System.out.println("Look for: Get Neovim opens at the first start; once it has installed Neovim, a text file you open"
                + " attaches (Neovim's modes in the status bar). docs/MANUAL_CHECKS.md, rl-publish.");
    }
}
