// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package eclipse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import common.Ask;
import common.Changelog;
import common.EclipseRelease;
import common.Git;
import common.Settings;
import common.Shell;
import common.ToolFailure;
import common.VersionCopies;

/**
 * The Eclipse tool: moves either end of the Eclipse range the plug-in supports
 * (docs/UPGRADING.md, "Eclipse release").
 * <pre>
 *   java releng/tools/eclipse/EclipseUpgrade.java newest              test with the newest Eclipse release
 *   java releng/tools/eclipse/EclipseUpgrade.java oldest 2025-12      raise the oldest supported release
 *   java releng/tools/eclipse/EclipseUpgrade.java smoke               install this checkout into both, nothing else
 *   flags: --yes, --dry-run (as the Release tool), --no-tests, --java &lt;Java home&gt; (the Java Eclipse runs on)
 * </pre>
 *
 * <h2>Why two ends, and where each lives</h2>
 * The <b>oldest supported</b> release is the one the plug-in is compiled against
 * ({@code <eclipse-repo.url>} in the root {@code pom.xml}): a plug-in built against 2024-12 runs on
 * 2024-12 and every later release that keeps those APIs, so that is the promise "2024-12 or
 * newer". It moves only when the plug-in needs something newer, a few times at most. The
 * <b>newest tested</b> release is the second entry of the update site's smoke job
 * ({@code .github/workflows/update-site.yml}): every release of the plug-in is installed into it
 * before anyone else gets it, so the top of the range is tried too. It moves with each Eclipse
 * release, every March, June, September and December. Both are also written into the docs, by
 * name and number ({@code releng/version-copies.txt}, sections {@code [eclipse.*]}), which the tool
 * rewrites and {@code VersionConsistencyTest} checks.
 *
 * <h2>The steps of {@code newest}</h2>
 * <ol>
 *   <li>The newest Platform release from {@code data.json}, for example drop
 *       {@code R-4.42-202611271200}, 2026-12 (4.42); nothing to do if the smoke job has it.</li>
 *   <li>Its Linux x86_64 Platform's SHA-512 for CI, and this machine's Platform, downloaded and
 *       checked against the same checksums file ({@link Platform}).</li>
 *   <li>Its {@code eclipse.ini} must start on the Java in {@code .java-version}, which CI's smoke
 *       job runs it with; if not, the Java upgrade comes first.</li>
 *   <li>The edits: the smoke entry in {@code update-site.yml} and {@code rehearse-release.yml}, the
 *       {@code [eclipse.newest]} copies, an entry under <i>Unreleased</i>, <i>Changed</i>.</li>
 *   <li>The update site built from this checkout and installed into that Eclipse ({@link Smoke}).</li>
 *   <li>The commit, after y/N.</li>
 * </ol>
 *
 * <h2>The steps of {@code oldest <release>}</h2>
 * The same downloads for that release (its drop found on its p2 repository's page), then: stop if
 * it needs a newer Java (that upgrade is its own commit, first); read the eight test jars'
 * versions from its {@code plugins/} folder; set {@code <eclipse-repo.url>} and those
 * {@code <eclipse.*.version>} properties, the first smoke entry, the {@code [eclipse.oldest]}
 * copies and the changelog; run every test ({@code LikeCi --all}) and install into the new oldest
 * and the newest Eclipse; commit.
 *
 * <p>Every step before the commit only reads, downloads into {@code target/eclipse-upgrade/} or
 * edits the working tree; if one fails, the tree is put back as it was ({@link Git#editAndCommit}).
 */
public final class EclipseUpgradeTool {

    static final String TOOL = "releng/tools/eclipse/EclipseUpgrade.java";
    static final String UPDATE_SITE_YML = ".github/workflows/update-site.yml";
    static final String REHEARSAL_YML = ".github/workflows/rehearse-release.yml";
    private static final List<String> WORKFLOWS = List.of(UPDATE_SITE_YML, REHEARSAL_YML);
    private static final Pattern REPO_RELEASE = Pattern.compile("/releases/(20\\d\\d-\\d\\d)$");
    private static final Pattern POM_VERSION = Pattern.compile("<artifactId>neovim4eclipse</artifactId>\\s*<version>([^<]+)</version>");
    private static final Pattern DROP_IN_URL = Pattern.compile("/(R-4\\.\\d+-\\d{12})/");
    private static final String USAGE = "Usage: java " + TOOL + " newest | oldest <release> | smoke"
            + " [--no-tests] [--java <Java home>] [--yes] [--dry-run]";

    /** The two smoke entries, by their place in the matrix. */
    static final int OLDEST = 0;
    static final int NEWEST = 1;

    private final Path root;
    private final Shell shell;
    private final Git git;
    private final Settings settings;
    private final Ask ask;
    private final boolean tests;
    /** The Java home given with --java or eclipse.java, or empty; resolved by {@link #javaHome()} when an install needs it. */
    private final String javaGiven;
    private Path javaHome;
    private final String os = EclipseFiles.os(System.getProperty("os.name"));
    private final String arch = EclipseFiles.arch(System.getProperty("os.arch"));

    private EclipseUpgradeTool(Path root, Ask ask, boolean tests, String java) {
        this.root = root;
        this.shell = new Shell(root);
        this.git = new Git(shell);
        this.settings = Settings.load(List.of(root.resolve(Settings.COMMON)), Map.of());
        this.ask = ask;
        this.tests = tests;
        this.javaGiven = java != null ? java : settings.get("eclipse.java", "");
    }

    public static void main(String[] args) {
        try {
            Path root = Path.of("").toAbsolutePath();
            if (!Files.isRegularFile(root.resolve(TOOL))) {
                throw new ToolFailure("run this from the repository's root folder: java " + TOOL + " newest");
            }
            List<String> words = new ArrayList<>();
            boolean tests = true;
            String java = null;
            Ask.Mode mode = Ask.Mode.ASK;
            List<String> rerun = new ArrayList<>(List.of("java", TOOL));
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--no-tests" -> tests = false;
                    case "--yes" -> mode = mode == Ask.Mode.DRY_RUN ? mode : Ask.Mode.YES;
                    case "--dry-run" -> mode = Ask.Mode.DRY_RUN;
                    case "--java" -> {
                        if (i + 1 >= args.length) {
                            throw new ToolFailure("--java needs a Java home folder\n" + USAGE);
                        }
                        java = args[++i];
                        rerun.add(a);
                        a = java;
                    }
                    default -> {
                        if (a.startsWith("-")) {
                            throw new ToolFailure("unknown argument " + a + "\n" + USAGE);
                        }
                        words.add(a);
                    }
                }
                if (!a.equals("--yes") && !a.equals("--dry-run")) {
                    rerun.add(a);
                }
            }
            rerun.add("--yes");
            EclipseUpgradeTool tool = new EclipseUpgradeTool(root, new Ask(mode, String.join(" ", rerun)), tests, java);
            String command = words.isEmpty() ? "" : words.get(0);
            switch (command) {
                case "newest" -> {
                    expect(words, 1);
                    tool.newest();
                }
                case "oldest" -> {
                    expect(words, 2);
                    tool.oldest(EclipseRelease.ofName(words.get(1)));
                }
                case "smoke" -> {
                    expect(words, 1);
                    tool.smoke();
                }
                default -> throw new ToolFailure((command.isEmpty() ? "newest, oldest or smoke?" : "unknown command " + command) + "\n" + USAGE);
            }
        } catch (ToolFailure f) {
            System.err.println();
            System.err.println("EclipseUpgrade: " + f.getMessage());
            System.exit(1);
        }
    }

    private static void expect(List<String> words, int count) {
        if (words.size() != count) {
            throw new ToolFailure((words.size() < count ? "which release? " : "too many arguments: " + words + "\n") + USAGE);
        }
    }

    // ------------------------------------------------------------ newest

    private void newest() {
        checkBranch();
        String drop = EclipseFiles.newestDrop(Platform.text(EclipseFiles.DATA_JSON));
        EclipseFiles.checkDropDate(drop);
        EclipseRelease to = EclipseFiles.releaseOf(drop);
        EclipseRelease from = EclipseRelease.ofNumber(smokeEntry(NEWEST).eclipse());
        if (to.compareTo(from) <= 0) {
            System.out.println("ok: the smoke job already installs into Eclipse " + from + ", the newest release (" + drop + ")");
            return;
        }
        System.out.println("Eclipse " + to + " is out (" + drop + "); the newest tested is " + from + ".");
        Downloads d = downloads(drop, to);
        // Unpacked even with --no-tests: its eclipse.ini says which Java it needs, which CI's smoke job must have.
        Platform.Installation eclipse = unpack(d, to, tests);
        OptionalInt needs = requiredJava(eclipse);
        if (needs.isPresent() && needs.getAsInt() > javaVersionFile()) {
            throw new ToolFailure("Eclipse " + to + " needs Java " + needs.getAsInt() + ", but the smoke job runs it on Java "
                    + javaVersionFile() + " (.java-version): raise the Java release first (docs/UPGRADING.md, \"JDK\")");
        }
        if (tests) {
            checkJava(eclipse, to);
        }
        List<VersionCopies.Copy> names = copies("eclipse.newest");
        List<VersionCopies.Copy> numbers = copies("eclipse.newest.number");
        List<String> plan = new ArrayList<>(List.of(
                "smoke entry " + (NEWEST + 1) + " in " + String.join(" and ", WORKFLOWS) + ": " + to.number() + ", " + d.ciUrl(),
                "rewrite " + from.name() + " and " + from.number() + " to " + to.name() + " and " + to.number()
                        + " in " + files(names, numbers) + " (" + VersionCopies.FILE + ")",
                Changelog.FILE + ", Unreleased, Changed: " + EclipseFiles.newestEntry(to)));
        plan.add(tests ? "build the update site and install it into " + to + " (" + os + "-" + arch + ")" : "no install (--no-tests)");
        boolean committed = commit("Test every release with Eclipse " + to + ", the newest, then commit", plan, "Test with Eclipse " + to, () -> {
            setSmokeEntry(NEWEST, new EclipseRelease.SmokeEntry(to.number(), d.ciUrl(), d.ciSha512()));
            rewrite(names, to.name());
            rewrite(numbers, to.number());
            Changelog.Removed earlier = Changelog.withoutEntry(read(Changelog.FILE), EclipseFiles.NEWEST_ENTRY);
            write(Changelog.FILE, Changelog.withChangedEntry(earlier.changelog(), EclipseFiles.newestEntry(to)));
            ok(Changelog.FILE + ": the entry under Unreleased, Changed");
            if (tests) {
                buildSite();
                Smoke.install(root, eclipse, javaHome(), os, version(), work(to).resolve("director.log"));
            }
        });
        if (!committed) {
            return;
        }
        System.out.println();
        System.out.println("Now tested up to Eclipse " + to + ". What stays yours:");
        System.out.println("  - On macOS and Windows: java " + TOOL + " smoke");
        System.out.println("  - Once the plug-in is on the Eclipse Marketplace, tick " + to.name() + " in the listing's supported releases.");
    }

    // ------------------------------------------------------------ oldest

    private void oldest(EclipseRelease to) {
        checkBranch();
        EclipseRelease from = oldestNow();
        EclipseRelease newest = EclipseRelease.ofNumber(smokeEntry(NEWEST).eclipse());
        if (to.compareTo(from) <= 0) {
            throw new ToolFailure("the oldest supported Eclipse is " + from + " already; " + to + " is not newer");
        }
        if (to.compareTo(newest) >= 0) {
            throw new ToolFailure(to + " is not older than the newest tested Eclipse, " + newest + ": move that first (newest)");
        }
        String drop = EclipseFiles.dropIn(Platform.text(EclipseFiles.UPDATES + to.number() + "/"), to);
        EclipseFiles.checkDropDate(drop);
        Downloads d = downloads(drop, to);
        // Always unpacked: the test jars' versions come from its plugins/ folder.
        Platform.Installation eclipse = unpack(d, to, tests);
        int java = javaVersionFile();
        OptionalInt needs = requiredJava(eclipse);
        if (needs.isPresent() && needs.getAsInt() > java) {
            throw new ToolFailure("Eclipse " + to + " needs Java " + needs.getAsInt() + ", the plug-in is built for " + java
                    + ": raise the Java release first, in a commit of its own (docs/UPGRADING.md, \"JDK\"), then run this again");
        }
        if (tests) {
            checkJava(eclipse, to);
        }
        Map<String, String> jars = EclipseFiles.testJarVersions(pluginEntries(eclipse));
        List<VersionCopies.Copy> names = copies("eclipse.oldest");
        List<VersionCopies.Copy> numbers = copies("eclipse.oldest.number");
        List<String> plan = new ArrayList<>(List.of(
                "pom.xml: <eclipse-repo.url>" + EclipseFiles.repoUrl(to) + "</eclipse-repo.url>",
                "pom.xml: " + String.join(", ", jars.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList()),
                "smoke entry " + (OLDEST + 1) + " in " + String.join(" and ", WORKFLOWS) + ": " + to.number() + ", " + d.ciUrl(),
                "rewrite " + from.name() + " and " + from.number() + " to " + to.name() + " and " + to.number()
                        + " in " + files(names, numbers) + " (" + VersionCopies.FILE + ")",
                Changelog.FILE + ", Unreleased, Changed: " + EclipseFiles.oldestEntry(from, to)));
        plan.add(tests ? "java releng/tools/ci/LikeCi.java --all; install the built site into " + to + " and " + newest
                : "no tests (--no-tests)");
        boolean committed = commit("Raise the oldest supported Eclipse from " + from + " to " + to + ", then commit", plan,
                "Move the oldest supported Eclipse to " + to, () -> {
                    String pom = EclipseFiles.withProperty(read("pom.xml"), "eclipse-repo.url", EclipseFiles.repoUrl(to));
                    for (Map.Entry<String, String> jar : jars.entrySet()) {
                        pom = EclipseFiles.withProperty(pom, jar.getKey(), jar.getValue());
                    }
                    write("pom.xml", pom);
                    ok("pom.xml: " + to.name() + " and its test jars");
                    setSmokeEntry(OLDEST, new EclipseRelease.SmokeEntry(to.number(), d.ciUrl(), d.ciSha512()));
                    rewrite(names, to.name());
                    rewrite(numbers, to.number());
                    Changelog.Removed earlier = Changelog.withoutEntry(read(Changelog.FILE), EclipseFiles.OLDEST_ENTRY);
                    // Two raises before a release (2025-06, then 2025-12): users had the released floor, not the one between.
                    EclipseRelease was = earlier.entry() == null ? from : EclipseFiles.wasIn(earlier.entry()).orElse(from);
                    write(Changelog.FILE, Changelog.withChangedEntry(earlier.changelog(), EclipseFiles.oldestEntry(was, to)));
                    ok(Changelog.FILE + ": the entry under Unreleased, Changed");
                    if (tests) {
                        if (shell.show(Shell.java("releng/tools/ci/LikeCi.java", "--all")) != 0) {
                            throw new ToolFailure("the tests failed against Eclipse " + to + " (see above)");
                        }
                        ok("every test passed against Eclipse " + to);
                        Smoke.install(root, eclipse, javaHome(), os, version(), work(to).resolve("director.log"));
                        smokeInto(newest, dropOf(smokeEntry(NEWEST)));
                    }
                });
        if (!committed) {
            return;
        }
        System.out.println();
        System.out.println("The plug-in now needs Eclipse " + to + " or newer. What stays yours:");
        System.out.println("  - On macOS and Windows: java releng/tools/ci/LikeCi.java --all, then java " + TOOL + " smoke");
        System.out.println("  - The release notes: users of an older Eclipse keep their installed version and get no updates.");
        System.out.println("  - Remove each workaround for a release older than " + to.name() + ", in a commit of its own;");
        System.out.println("    and read the comments that name " + from.number() + " for one release only (4.34's eclipse.ini, Java 24).");
    }

    // ------------------------------------------------------------ smoke

    private void smoke() {
        List<EclipseRelease.SmokeEntry> entries = List.of(smokeEntry(OLDEST), smokeEntry(NEWEST));
        buildSite();
        for (EclipseRelease.SmokeEntry e : entries) {
            smokeInto(EclipseRelease.ofNumber(e.eclipse()), dropOf(e));
        }
        ok("this checkout installs into Eclipse " + String.join(" and ", entries.stream().map(e -> e.eclipse()).toList())
                + " on " + os + "-" + arch);
    }

    /** Downloads, unpacks and smoke-installs into one pinned release, on this machine. */
    private void smokeInto(EclipseRelease release, String drop) {
        Platform.Installation eclipse = unpack(downloads(drop, release), release, true);
        checkJava(eclipse, release);
        Smoke.install(root, eclipse, javaHome(), os, version(), work(release).resolve("director.log"));
    }

    // ------------------------------------------------------------ shared steps

    /**
     * A drop's two downloads: the one CI pins (URL and SHA-512), and this machine's, whose URL and
     * SHA-512 are {@code null} when the drop has no Platform for it (Windows on ARM in older drops).
     */
    record Downloads(String ciUrl, String ciSha512, String hostUrl, String hostSha512) {
    }

    private Downloads downloads(String drop, EclipseRelease release) {
        String checksums = Platform.textOrNull(EclipseFiles.checksums(drop));
        if (checksums == null) {
            checksums = Platform.textOrNull(EclipseFiles.oldChecksums(drop));
        }
        if (checksums == null) {
            throw new ToolFailure(drop + " has no checksums file (" + EclipseFiles.checksums(drop) + ", or "
                    + EclipseFiles.oldChecksums(drop) + ")");
        }
        String ci = EclipseFiles.ciPlatformFile(release);
        String host = EclipseFiles.platformFile(release, os, arch);
        String ciSha = EclipseFiles.sha512In(checksums, ci)
                .orElseThrow(() -> new ToolFailure(drop + "'s checksums file has no " + ci));
        String hostSha = EclipseFiles.sha512In(checksums, host).orElse(null);
        String base = EclipseFiles.DROPS + drop + "/";
        return new Downloads(base + ci, ciSha, hostSha == null ? null : base + host, hostSha);
    }

    /**
     * A release's Platform, downloaded (or kept) and unpacked into {@link #work}: this machine's
     * when an install will run it ({@code runs}), else CI's Linux x86_64 one, whose
     * {@code eclipse.ini} and {@code plugins/} are what CI's smoke job and the build get (unpacked
     * with {@code tar}, which macOS and Windows 10 and later have too).
     */
    private Platform.Installation unpack(Downloads d, EclipseRelease release, boolean runs) {
        String url = runs ? d.hostUrl() : d.ciUrl();
        String sha = runs ? d.hostSha512() : d.ciSha512();
        if (url == null) {
            throw new ToolFailure("Eclipse " + release + " has no Platform for this machine (" + EclipseFiles.platformFile(release, os, arch)
                    + "), so nothing can be installed into it here: run this on another machine, or newest/oldest with --no-tests");
        }
        Path archive = Platform.download(url, sha, root.resolve("target/eclipse-upgrade/downloads"));
        Platform.Installation eclipse = Platform.unpack(archive, work(release).resolve(runs ? "platform" : "ci-platform"), shell); // CI's is Linux x86_64 whatever this machine is
        ok("Eclipse " + release + " unpacked: " + eclipse.program());
        return eclipse;
    }

    private Path work(EclipseRelease release) {
        return root.resolve("target/eclipse-upgrade/" + release.number() + "-" + os + "-" + arch);
    }

    /** The Java release {@code eclipse.ini} asks for ({@code -Dosgi.requiredJavaVersion}), if it does. */
    private static OptionalInt requiredJava(Platform.Installation eclipse) {
        return EclipseFiles.requiredJava(read(eclipse.inner().resolve("eclipse.ini")));
    }

    /**
     * Stops unless the Java the installs run on ({@link #javaHome()}) is one {@code eclipse} starts
     * on: at least its {@code -Dosgi.requiredJavaVersion}. A newer Java is no proof (4.34's
     * {@code eclipse.ini} stops on Java 24+), which the install then shows.
     */
    private void checkJava(Platform.Installation eclipse, EclipseRelease release) {
        OptionalInt needs = requiredJava(eclipse);
        int have = javaRelease(javaHome());
        if (needs.isPresent() && needs.getAsInt() > have) {
            throw new ToolFailure("Eclipse " + release + " needs Java " + needs.getAsInt() + "; " + javaHome() + " is Java " + have
                    + ": name a Java it starts on with --java, or eclipse.java in releng/tools/common/local.properties");
        }
        ok("Eclipse " + release + " starts on Java " + (needs.isPresent() ? needs.getAsInt() + "+" : "(any)") + "; installing with "
                + javaHome() + " (Java " + have + ")");
    }

    private void buildSite() {
        System.out.println(Shell.display(Smoke.buildSite()));
        if (shell.show(Smoke.buildSite()) != 0) {
            throw new ToolFailure("the update site did not build (see above)");
        }
    }

    /** {@link Git#editAndCommit}: the edits, their checks and the commit after y/N; false if it was not made (--dry-run). */
    private boolean commit(String what, List<String> plan, String message, Runnable edit) {
        String trailer = settings.get("commit.trailer", "");
        return git.editAndCommit(ask, what, plan, trailer.isEmpty() ? message : message + "\n\n" + trailer, () -> {
            System.out.println();
            edit.run();
            return git.out("diff", "--name-only").lines().toList();
        });
    }

    private void checkBranch() {
        String branch = git.branch();
        if (!branch.startsWith("release/") && !branch.startsWith("fix/") && !branch.startsWith("feature/")) {
            throw new ToolFailure("this is " + (branch.isEmpty() ? "a detached HEAD" : branch)
                    + "; move Eclipse on the release branch or a fix/ branch cut from it (CONTRIBUTING.md, \"Branches\")");
        }
    }

    private EclipseRelease oldestNow() {
        Matcher m = Pattern.compile("<eclipse-repo\\.url>([^<]+)</eclipse-repo\\.url>").matcher(read("pom.xml"));
        if (!m.find()) {
            throw new ToolFailure("pom.xml has no <eclipse-repo.url>");
        }
        Matcher name = REPO_RELEASE.matcher(m.group(1).strip());
        if (!name.find()) {
            throw new ToolFailure("<eclipse-repo.url> should end in /releases/<release>, such as /releases/2024-12: " + m.group(1));
        }
        return EclipseRelease.ofName(name.group(1));
    }

    private EclipseRelease.SmokeEntry smokeEntry(int index) {
        List<EclipseRelease.SmokeEntry> entries = EclipseRelease.smokeEntries(read(UPDATE_SITE_YML));
        if (entries.size() != 2) {
            throw new ToolFailure(UPDATE_SITE_YML + "'s smoke matrix should have two entries, the oldest and the newest Eclipse, not "
                    + entries.size());
        }
        return entries.get(index);
    }

    private static String dropOf(EclipseRelease.SmokeEntry entry) {
        Matcher m = DROP_IN_URL.matcher(entry.platformUrl());
        if (!m.find()) {
            throw new ToolFailure("the smoke entry " + entry.eclipse() + " downloads from no drop R-4.x-<time>: " + entry.platformUrl());
        }
        return m.group(1);
    }

    private void setSmokeEntry(int index, EclipseRelease.SmokeEntry entry) {
        for (String workflow : WORKFLOWS) {
            write(workflow, EclipseRelease.withSmokeEntry(read(workflow), index, entry));
        }
        ok("smoke entry " + (index + 1) + " in " + String.join(" and ", WORKFLOWS) + ": Eclipse " + entry.eclipse());
    }

    private List<VersionCopies.Copy> copies(String section) {
        List<VersionCopies.Copy> copies = VersionCopies.parse(read(VersionCopies.FILE)).get(section);
        if (copies == null) {
            throw new ToolFailure(VersionCopies.FILE + " has no [" + section + "] section");
        }
        return copies;
    }

    /** The files both sections' copies are in, each once, in list order. */
    private static String files(List<VersionCopies.Copy> names, List<VersionCopies.Copy> numbers) {
        Set<String> files = new LinkedHashSet<>();
        Stream.concat(names.stream(), numbers.stream()).forEach(c -> files.add(c.file()));
        return String.join(", ", files);
    }

    private void rewrite(List<VersionCopies.Copy> copies, String version) {
        for (VersionCopies.Copy copy : copies) {
            String text = read(copy.file());
            if (VersionCopies.versionsIn(text, copy).isEmpty()) {
                throw new ToolFailure(copy.file() + ": nothing matches " + copy.pattern() + " (" + VersionCopies.FILE + ")");
            }
            write(copy.file(), VersionCopies.rewrite(text, copy, version));
        }
        ok("the copies in " + VersionCopies.FILE + " name " + version);
    }

    /** This checkout's version without {@code -SNAPSHOT}, as p2 has it before the qualifier: {@code 0.2.10}. */
    private String version() {
        Matcher m = POM_VERSION.matcher(read("pom.xml"));
        if (!m.find()) {
            throw new ToolFailure("pom.xml has no version for neovim4eclipse");
        }
        return m.group(1).replace("-SNAPSHOT", "");
    }

    private int javaVersionFile() {
        try {
            return Integer.parseInt(read(".java-version").strip());
        } catch (NumberFormatException e) {
            throw new ToolFailure(".java-version should hold one number, such as 21");
        }
    }

    /**
     * The Java home the installs run on: {@code --java}, else {@code eclipse.java} in
     * {@code releng/tools/common/local.properties}, else {@code JAVA_HOME}, else the Java running
     * this tool. Example: {@code --java /usr/lib/jvm/java-21-openjdk-amd64} for Eclipse 4.34,
     * whose {@code eclipse.ini} does not start on Java 24 and newer.
     */
    private Path javaHome() {
        if (javaHome == null) {
            javaHome = javaHome(javaGiven);
        }
        return javaHome;
    }

    private static Path javaHome(String given) {
        String home = !given.isBlank() ? given : System.getenv().getOrDefault("JAVA_HOME", "");
        Path path = Path.of(home.isBlank() ? System.getProperty("java.home") : home).toAbsolutePath();
        if (!Files.isRegularFile(path.resolve("release"))) {
            throw new ToolFailure(path + " is not a Java home (it has no release file); name one with --java");
        }
        return path;
    }

    /** A Java home's feature release, from {@code JAVA_VERSION="21.0.5"} in its {@code release} file. */
    private static int javaRelease(Path home) {
        try {
            return EclipseFiles.javaRelease(Files.readString(home.resolve("release")));
        } catch (IOException e) {
            throw new ToolFailure("cannot read " + home.resolve("release") + ": " + e.getMessage());
        }
    }

    private List<String> pluginEntries(Platform.Installation eclipse) {
        try (Stream<Path> entries = Files.list(eclipse.inner().resolve("plugins"))) {
            return entries.map(p -> p.getFileName().toString()).toList();
        } catch (IOException e) {
            throw new ToolFailure("cannot list " + eclipse.inner().resolve("plugins") + ": " + e.getMessage());
        }
    }

    private String read(String file) {
        return read(root.resolve(file));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
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
