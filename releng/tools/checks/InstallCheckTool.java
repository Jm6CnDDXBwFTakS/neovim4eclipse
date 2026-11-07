// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import common.Ask;
import common.EclipseCopy;
import common.Folders;
import common.PgpKey;
import common.Settings;
import common.ToolFailure;
import common.UpdateSite;
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
 *   <li>{@code -trustedCertificates} with no value: no jar signed with a self-made ("unanchored")
 *       certificate. Without it the director trusts every such certificate, and
 *       {@code -trustSignedContentOnly} alone only refuses what is not signed at all;</li>
 *   <li>{@code -trustSignedContentOnly}: no unsigned artifact.</li>
 * </ul>
 * A jar signed with a certificate from an authority Java trusts is accepted by p2 without asking,
 * whatever the arguments, and releases are never jar-signed. So the tool also reads the director's
 * report ({@code -verboseTrust}, which lists what each key signed) and passes only if the release
 * key signed the plug-in and the feature and nothing else is listed ({@link #trustProblem}). An
 * artifact signed otherwise, or not signed, fails, where a user could click <i>Trust</i> on it.
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
 * <p>The folder is deleted at the start of each run, so the tool refuses a {@code --root} it did
 * not make: one that holds files but not its mark ({@link #MARK}). Example: {@code --root
 * ~/var/n4e-checks}, the manual checks' folder, stops before anything is deleted.
 *
 * <p>When the install passed, it records the version in {@code target/release-tool/install-checked}
 * ({@link #RECORD}), which the Release tool's status reads.
 */
public final class InstallCheckTool {

    static final String TOOL = "releng/tools/checks/InstallCheck.java";

    /** Where a passed install is recorded, from the repository's root: the version and the time. */
    public static final String RECORD = "target/release-tool/install-checked";

    /** Where the copy goes unless {@code --root} says otherwise. */
    static final Path DEFAULT_ROOT = Path.of(System.getProperty("user.home"), "var", "n4e-checks", "install-check");

    /** The file that marks a folder as this tool's: only such a folder, or an empty one, is deleted. */
    static final String MARK = ".install-check";

    /** The plug-in's bundle, as the director's trust report names its artifact. */
    private static final String BUNDLE = "io.github.jm6cnddxbwftaks.neovim4eclipse";

    /** The p2 director's application, run inside the Eclipse copy. */
    private static final List<String> DIRECTOR = List.of("--launcher.suppressErrors", "-nosplash", "-application",
            "org.eclipse.equinox.p2.director");

    private InstallCheckTool() {
    }

    /**
     * What the command line asked for. {@code named} keeps the {@code --eclipse} and {@code --root}
     * arguments as given, so a command the tool prints to run it again ({@link #startCommand}) finds
     * the same Eclipse and folder.
     */
    record Options(Optional<Version> version, Path root, String eclipse, Ask.Mode mode, boolean launch,
            List<String> named) {

        static Options parse(String[] args) {
            Optional<Version> version = Optional.empty();
            Path root = DEFAULT_ROOT;
            String eclipse = System.getenv("ECLIPSE");
            Ask.Mode mode = Ask.Mode.ASK;
            boolean launch = true;
            List<String> named = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--root" -> {
                        root = Path.of(value(args, ++i));
                        named.addAll(List.of("--root", args[i]));
                    }
                    case "--eclipse" -> {
                        eclipse = value(args, ++i);
                        named.addAll(List.of("--eclipse", args[i]));
                    }
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
            return new Options(version, root.toAbsolutePath(), eclipse, mode, launch, List.copyOf(named));
        }

        /**
         * The command that installs {@code v} again and starts the copy without asking, with this
         * run's {@code --eclipse} and {@code --root}: without them a machine whose Eclipse is not
         * on PATH would stop at once. Example:
         * {@code java releng/tools/checks/InstallCheck.java 0.2.5 --eclipse /opt/eclipse/current/eclipse --yes}.
         * An argument with a space or a shell character is quoted for the shell it is pasted into
         * ({@link #shellWord}).
         */
        String startCommand(Version v) {
            return startCommand(v, System.getProperty("os.name").startsWith("Windows"));
        }

        /** {@link #startCommand(Version)} for Windows' shells or for a POSIX shell. */
        String startCommand(Version v, boolean windows) {
            StringBuilder command = new StringBuilder("java ").append(TOOL).append(' ').append(v);
            for (String arg : named) {
                command.append(' ').append(shellWord(arg, windows));
            }
            return command.append(" --yes").toString();
        }

        /**
         * {@code arg} as one word of a pasted command. Plain words stay as they are. A POSIX shell
         * gets single quotes, inside which nothing expands ({@code '} itself becomes {@code '\''}):
         * {@code /tmp/a$b c} gives {@code '/tmp/a$b c'}. Windows gets double quotes (cmd and
         * PowerShell), which a Windows path cannot contain.
         */
        static String shellWord(String arg, boolean windows) {
            if (arg.matches("[A-Za-z0-9_./:=@+,-]+")) {
                return arg;
            }
            return windows ? '"' + arg + '"' : "'" + arg.replace("'", "'\\''") + "'";
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
        String listing = UpdateSite.get(site + "compositeContent.xml");
        if (listing == null) {
            throw new ToolFailure("cannot read " + site + "compositeContent.xml");
        }
        List<Version> released = UpdateSite.releases(listing);
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
        if (!mayDelete(root)) {
            throw new ToolFailure(root + " holds files but is not an InstallCheck folder (no " + MARK + " in it), so it is not"
                    + " deleted. Name an empty or new folder with --root, or leave it out for ~/var/n4e-checks/install-check");
        }
        try {
            Folders.deleteTree(root); // links and copies only: your Eclipse keeps its files
        } catch (ToolFailure f) {
            throw new ToolFailure(f.getMessage() + ". Close whatever still uses a file there (an Eclipse or Neovim from an earlier"
                    + " run), then run this again");
        }
        Files.createDirectories(root);
        Files.writeString(root.resolve(MARK), "Made by " + TOOL + ", which deletes this folder at its next run.\n");
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
        Optional<String> distrust = trustProblem(output, fingerprint, v);
        if (distrust.isPresent()) {
            throw new ToolFailure("the install of " + v + " is not trusted as a release: " + distrust.get()
                    + ". Do not start this Eclipse; look at the update site (" + log + ")");
        }
        Optional<String> installed = installedVersion(director, log);
        if (installed.isEmpty() || !isVersion(installed.get(), v)) {
            throw new ToolFailure("after the install the copy has " + installed.map(s -> "version " + s).orElse("no plug-in")
                    + ", not " + v + " (" + log + ")");
        }
        System.out.println("ok: " + v + " installs from the update site, every artifact signed by the release key");
        record(repo, v);

        if (options.launch() && new Ask(options.mode(), "").offer("Start this Eclipse for a look (a new workspace and an empty"
                + " Neovim folder: Get Neovim opens at its first start)", options.startCommand(v))) {
            start(program, root, workspace);
        }
    }

    /**
     * Whether a run may delete {@code root} to start afresh: it does not exist, is empty, holds this
     * tool's {@link #MARK}, or is the default folder (made by runs from before the mark).
     */
    static boolean mayDelete(Path root) throws IOException {
        if (!Files.exists(root) || root.equals(DEFAULT_ROOT.toAbsolutePath()) || Files.isRegularFile(root.resolve(MARK))) {
            return true;
        }
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (var entries = Files.list(root)) {
            return entries.findAny().isEmpty();
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
     * -uninstallIU <feature> -installIU <feature> -trustSignedContentOnly -trustedCertificates -trustedPGPKeys a91f…063c
     * -trustedAuthorities https://jm6cnddxbwftaks.github.io/ -verboseTrust -data @none}.
     */
    static List<String> installArguments(String site, Version v, String fingerprint, boolean installed) {
        List<String> args = new ArrayList<>(DIRECTOR);
        args.addAll(List.of("-repository", site + "releases/" + v + "/"));
        if (installed) {
            args.addAll(List.of("-uninstallIU", EclipseCopy.FEATURE));
        }
        // -trustedCertificates with no value (the next argument is an option) trusts no self-made certificate.
        args.addAll(List.of("-installIU", EclipseCopy.FEATURE, "-trustSignedContentOnly", "-trustedCertificates",
                "-trustedPGPKeys", fingerprint.toLowerCase(java.util.Locale.ROOT), "-trustedAuthorities", authority(site),
                "-verboseTrust", "-data", "@none"));
        return args;
    }

    /** The site's scheme and host, with the {@code /} the director needs: {@code https://jm6cnddxbwftaks.github.io/}. */
    static String authority(String site) {
        URI uri = URI.create(site);
        return uri.getScheme() + "://" + uri.getAuthority() + "/";
    }

    /** Whether p2's version of the feature is release {@code v}: {@code 0.2.4}, or {@code 0.2.4.<qualifier>}. */
    static boolean isVersion(String p2Version, Version v) {
        return p2Version.equals(v.toString()) || p2Version.startsWith(v + ".");
    }

    /**
     * The director's output without the log lines of Eclipse's own bundles starting up (a date line
     * and an {@code INFO:} line each, dozens of them, in English as {@link EclipseCopy#run} runs it)
     * and Java's note about {@code JAVA_TOOL_OPTIONS}, leaving what it installed and trusted.
     */
    static List<String> directorLines(String output) {
        return output.lines().filter(l -> !l.isBlank() && !l.startsWith("INFO: ") && !l.startsWith("Picked up JAVA_TOOL_OPTIONS")
                && !l.matches("[A-Z][a-z]{2} \\d{1,2}, \\d{4} \\d{1,2}:\\d{2}:\\d{2} [AP]M .*")).toList();
    }

    /**
     * What is wrong with the director's trust report ({@code -verboseTrust}) for a release, if
     * anything. A release passes only if the report lists the plug-in's bundle and the feature
     * under the release key, and no other heading: no other key, no certificate chain, nothing
     * unsigned. Example of a good report:
     * <pre>
     * The following artifacts are signed by PGP key a91f53d8931b7fd0d14686186cc9e446147b063c
     *   osgi.bundle,io.github.jm6cnddxbwftaks.neovim4eclipse,0.2.4 -> /.../plugins/...jar
     *   org.eclipse.update.feature,io.github.jm6cnddxbwftaks.neovim4eclipse.feature,0.2.4 -> /.../features/...
     * </pre>
     */
    static Optional<String> trustProblem(String output, String fingerprint, Version v) {
        String ours = "The following artifacts are signed by PGP key " + fingerprint.toLowerCase(java.util.Locale.ROOT);
        String feature = EclipseCopy.FEATURE.substring(0, EclipseCopy.FEATURE.length() - ".feature.group".length());
        boolean underOurs = false;
        boolean bundle = false;
        boolean featureSigned = false;
        for (String raw : output.lines().toList()) {
            String line = raw.strip();
            if (line.startsWith("The following artifacts are") || line.startsWith("The artifacts below are")) {
                if (!line.equals(ours)) {
                    return Optional.of("the director reports \"" + line + "\"");
                }
                underOurs = true;
            } else if (underOurs && raw.startsWith("  ")) {
                String artifact = line.split(" -> ")[0];
                bundle |= isArtifact(artifact, "osgi.bundle," + BUNDLE + ",", v);
                featureSigned |= isArtifact(artifact, "org.eclipse.update.feature," + feature + ",", v);
            } else {
                underOurs = false;
            }
        }
        if (!bundle || !featureSigned) {
            return Optional.of("the release key did not sign " + (bundle ? "the feature" : featureSigned ? "the plug-in" : "the plug-in and the feature")
                    + " (no such line after \"" + ours + "\")");
        }
        return Optional.empty();
    }

    private static boolean isArtifact(String artifact, String prefix, Version v) {
        return artifact.startsWith(prefix) && isVersion(artifact.substring(prefix.length()), v);
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
