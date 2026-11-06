// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package neovim;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

import common.Ask;
import common.Folders;
import common.Git;
import common.Settings;
import common.Shell;
import common.ToolFailure;
import common.Version;
import common.VersionCopies;

/**
 * The Neovim tool: moves the plug-in to a new Neovim release, which then is both what
 * <i>Get Neovim</i> installs and the oldest Neovim the plug-in runs (docs/UPGRADING.md, "Neovim").
 * <pre>
 *   java releng/tools/neovim/NeovimUpgrade.java 0.13.0                 the move, asking before it
 *   java releng/tools/neovim/NeovimUpgrade.java 0.13.0 --allow-young   for a release under a week old
 *   flags: --yes, --dry-run (as the Release tool), --no-tests (skip LikeCi)
 * </pre>
 *
 * <h2>The steps, each of which must pass</h2>
 * It asks once, then edits, checks and commits; if any step fails, the tree is put back as it was
 * ({@link Git#editAndCommit}).
 * <ol>
 *   <li><b>The data file</b>: {@code java releng/UpdateNeovimDownloads.java 0.13.0} rewrites
 *       {@code util/neovim-downloads.properties} (the six archives' names, sizes, SHA-256 and tree
 *       hashes, each download compared with GitHub's own digest; it refuses a release under a week
 *       old) and prints each archive's VirusTotal page. The tool asks whether those were looked at.
 *       {@code NeovimVersion.MINIMUM} is read from that file, so the minimum moves with it.</li>
 *   <li><b>The copies</b> in {@code releng/version-copies.txt}, section {@code [neovim]}: the
 *       docs, the feature's description, the update site's category ({@link VersionCopies}).</li>
 *   <li><b>The changelog</b>: an entry under <i>Unreleased</i>, <i>Changed</i> ({@link NeovimFiles}).</li>
 *   <li><b>The archives, as Get Neovim installs them</b>: the plug-in is compiled, the six archives
 *       are downloaded into {@code target/neovim-upgrade/<version>/}, and {@link ArchiveCheck}
 *       unpacks each with the plug-in's own extractor and checks its tree hash; then this
 *       machine's Neovim must run.</li>
 *   <li><b>Every test</b> with the new Neovim: {@code java releng/tools/ci/LikeCi.java --all},
 *       which installs exactly the Neovim of the data file, as CI does.</li>
 *   <li><b>The commit</b>, {@code Move to Neovim 0.13.0}, in UTC.</li>
 * </ol>
 * Then it prints what stays a person's work: the release notes, and the workarounds the new
 * Neovim makes unnecessary, each removed in a commit of its own.
 */
public final class NeovimUpgradeTool {

    static final String TOOL = "releng/tools/neovim/NeovimUpgrade.java";
    static final String PINS = "plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/src/io/github/jm6cnddxbwftaks/neovim4eclipse/util/neovim-downloads.properties";
    static final String PLUGIN = "plugins/io.github.jm6cnddxbwftaks.neovim4eclipse";

    private final Path root;
    private final Shell shell;
    private final Git git;
    private final Settings settings;
    private final Ask ask;
    private final Version target;
    private final boolean allowYoung;
    private final boolean tests;

    private NeovimUpgradeTool(Path root, Version target, boolean allowYoung, boolean tests, Ask ask) {
        this.root = root;
        this.shell = new Shell(root);
        this.git = new Git(shell);
        // The settings every tool shares (commit.trailer); this tool has none of its own.
        this.settings = Settings.load(List.of(root.resolve(Settings.COMMON)), Map.of());
        this.ask = ask;
        this.target = target;
        this.allowYoung = allowYoung;
        this.tests = tests;
    }

    public static void main(String[] args) {
        try {
            Path root = Path.of("").toAbsolutePath();
            if (!Files.isRegularFile(root.resolve(TOOL))) {
                throw new ToolFailure("run this from the repository's root folder: java " + TOOL + " <version>");
            }
            Version target = null;
            boolean allowYoung = false;
            boolean tests = true;
            Ask.Mode mode = Ask.Mode.ASK;
            List<String> rerun = new ArrayList<>(List.of("java", TOOL));
            for (String a : args) {
                switch (a) {
                    case "--allow-young" -> allowYoung = true;
                    case "--no-tests" -> tests = false;
                    case "--yes" -> mode = mode == Ask.Mode.DRY_RUN ? mode : Ask.Mode.YES;
                    case "--dry-run" -> mode = Ask.Mode.DRY_RUN;
                    default -> {
                        if (target != null || a.startsWith("-")) {
                            throw new ToolFailure("unknown argument " + a + "\nUsage: java " + TOOL
                                    + " <version> [--allow-young] [--no-tests] [--yes] [--dry-run]");
                        }
                        target = Version.parse(a);
                    }
                }
                if (!a.equals("--yes") && !a.equals("--dry-run")) {
                    rerun.add(a);
                }
            }
            if (target == null) {
                throw new ToolFailure("which Neovim? Usage: java " + TOOL + " <version>, e.g. 0.13.0");
            }
            rerun.add("--yes");
            new NeovimUpgradeTool(root, target, allowYoung, tests, new Ask(mode, String.join(" ", rerun))).run();
        } catch (ToolFailure f) {
            System.err.println();
            System.err.println("NeovimUpgrade: " + f.getMessage());
            System.exit(1);
        }
    }

    private void run() {
        String branch = git.branch();
        if (!branch.startsWith("release/") && !branch.startsWith("fix/") && !branch.startsWith("feature/")) {
            throw new ToolFailure("this is " + (branch.isEmpty() ? "a detached HEAD" : branch)
                    + "; move Neovim on the release branch or a fix/ branch cut from it (CONTRIBUTING.md, \"Branches\")");
        }
        Version from = NeovimFiles.pinnedVersion(pins());
        if (target.compareTo(from) <= 0) {
            throw new ToolFailure("the plug-in is at Neovim " + from + " already; " + target + " is not newer");
        }
        Map<String, List<VersionCopies.Copy>> sections = VersionCopies.parse(read(VersionCopies.FILE));
        List<VersionCopies.Copy> copies = sections.getOrDefault("neovim", List.of());
        TreeSet<String> copyFiles = new TreeSet<>();
        copies.forEach(c -> copyFiles.add(c.file()));
        List<String> updater = new ArrayList<>(List.of("releng/UpdateNeovimDownloads.java", target.toString()));
        if (allowYoung) {
            updater.add("--allow-young");
        }
        List<String> plan = List.of(
                "java " + String.join(" ", updater),
                "rewrite the copies of " + from + " in " + String.join(", ", copyFiles) + " (" + VersionCopies.FILE + ")",
                "CHANGELOG.md, Unreleased, Changed: " + NeovimFiles.entry(from, target),
                "compile the plug-in; unpack all six archives with its extractor; run this machine's Neovim",
                tests ? "java releng/tools/ci/LikeCi.java --all, with Neovim " + target : "no tests (--no-tests)");
        String trailer = settings.get("commit.trailer", "");
        String message = "Move to Neovim " + target;
        boolean committed = git.editAndCommit(ask, "Move the plug-in from Neovim " + from + " to " + target + ", then commit", plan,
                trailer.isEmpty() ? message : message + "\n\n" + trailer, () -> {
                    move(from, updater, copies);
                    return git.out("diff", "--name-only").lines().toList();
                });
        if (committed) {
            done(from);
        }
    }

    /** Every step but the commit; a failure stops with a {@link ToolFailure} (and the tree is put back). */
    private void move(Version from, List<String> updater, List<VersionCopies.Copy> copies) {
        System.out.println();
        if (shell.show(Shell.java(updater.get(0), updater.subList(1, updater.size()).toArray(String[]::new))) != 0) {
            throw new ToolFailure("UpdateNeovimDownloads did not rewrite the data file (see above)");
        }
        Version pinned = NeovimFiles.pinnedVersion(pins());
        if (!pinned.equals(target)) {
            throw new ToolFailure("the data file holds " + pinned + " after the update, not " + target);
        }
        // The updater printed each archive's VirusTotal page: a person looks before the move goes on.
        ask.confirm("Looked at the six VirusTotal pages above, and they are clean? Then go on", List.of());

        for (VersionCopies.Copy copy : copies) {
            String text = read(copy.file());
            if (VersionCopies.versionsIn(text, copy).isEmpty()) {
                throw new ToolFailure(copy.file() + ": nothing matches " + copy.pattern() + " (" + VersionCopies.FILE + ")");
            }
            write(copy.file(), VersionCopies.rewrite(text, copy, target.toString()));
        }
        ok("the copies in " + VersionCopies.FILE + " name " + target);
        write("CHANGELOG.md", NeovimFiles.withNeovimEntry(read("CHANGELOG.md"), from, target));
        ok("CHANGELOG.md: the entry under Unreleased, Changed");

        checkArchives();
        if (tests) {
            if (shell.show(Shell.java("releng/tools/ci/LikeCi.java", "--all")) != 0) {
                throw new ToolFailure("the tests failed with Neovim " + target + " (see above)");
            }
            ok("every test passed with Neovim " + target);
        }
    }

    /** Downloads the six archives and lets {@link ArchiveCheck} unpack them with the plug-in's classes. */
    private void checkArchives() {
        Path folder = root.resolve("target/neovim-upgrade/" + target);
        Path archives = folder.resolve("archives");
        Path work = folder.resolve("unpacked");
        Folders.deleteTree(work);
        Properties pins = pins();
        String template = pins.getProperty("url", "");
        for (String key : new TreeSet<>(pins.stringPropertyNames())) {
            if (key.endsWith(".asset")) {
                String asset = pins.getProperty(key).strip();
                long size = Long.parseLong(pins.getProperty(key.replace(".asset", ".size"), "-1").strip());
                download(template.replace("{version}", target.toString()).replace("{asset}", asset), archives.resolve(asset), size);
            }
        }
        System.out.println(Shell.display(Shell.maven("-q", "-B", "-ntp", "-pl", PLUGIN, "compile")));
        if (shell.show(Shell.maven("-q", "-B", "-ntp", "-pl", PLUGIN, "compile")) != 0) {
            throw new ToolFailure("the plug-in did not compile (see above)");
        }
        List<String> check = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--class-path", root.resolve(PLUGIN + "/target/classes").toString(), "releng/tools/neovim/ArchiveCheck.java",
                root.resolve(PINS).toString(), archives.toString(), work.toString()));
        if (shell.show(check) != 0) {
            throw new ToolFailure("Get Neovim could not install the new archives as they are (see above)");
        }
    }

    /** Downloads {@code url} to {@code file}, unless it is there with {@code size} bytes already (ArchiveCheck checks the hash). */
    private static void download(String url, Path file, long size) {
        try {
            if (Files.isRegularFile(file) && Files.size(file) == size) {
                return;
            }
            Files.createDirectories(file.getParent());
            System.out.println("downloading " + url);
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(30)).build();
            HttpResponse<InputStream> r = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) {
                throw new ToolFailure(url + ": HTTP " + r.statusCode());
            }
            Path part = file.resolveSibling(file.getFileName() + ".part");
            try (InputStream in = r.body()) {
                Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ToolFailure("cannot download " + url + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("interrupted");
        }
    }

    /** What stays a person's work, printed after the commit. */
    private void done(Version from) {
        System.out.println();
        System.out.println("Moved to Neovim " + target + ". What stays yours:");
        System.out.println("  - On macOS and Windows: java releng/tools/ci/LikeCi.java --all");
        System.out.println("  - Read :help news and each patch release's notes from " + from + " to " + target
                + " (https://github.com/neovim/neovim/releases) for what the plug-in uses: core/rpc, LuaScripts, ext_cmdline,");
        System.out.println("    autocommand events, the options it sets.");
        System.out.println("  - Remove each workaround the new Neovim makes unnecessary, in a commit of its own; and the guards in");
        System.out.println("    LuaScripts.SETUP whose fix the new Neovim has (docs/UPGRADING.md, \"Following Neovim's releases\").");
    }

    private Properties pins() {
        Properties p = new Properties();
        try {
            p.load(new StringReader(read(PINS)));
        } catch (IOException e) {
            throw new ToolFailure("cannot read " + PINS + ": " + e.getMessage());
        }
        return p;
    }

    private String read(String file) {
        try {
            return Files.readString(root.resolve(file));
        } catch (IOException e) {
            throw new ToolFailure("cannot read " + file + ": " + e.getMessage());
        }
    }

    private void write(String file, String text) {
        try {
            Files.writeString(root.resolve(file), text);
        } catch (IOException e) {
            throw new ToolFailure("cannot write " + file + ": " + e.getMessage());
        }
    }

    private static void ok(String message) {
        System.out.println("ok: " + message);
    }
}
