// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package javarelease;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import common.Ask;
import common.Changelog;
import common.Git;
import common.Settings;
import common.Shell;
import common.ToolFailure;
import common.Version;
import common.VersionCopies;

/**
 * The Java tool: raises the Java release the plug-in needs, {@code <java.release>} in the root
 * {@code pom.xml}, with every copy of it, in one tested commit (docs/UPGRADING.md, "JDK / Java
 * release").
 * <pre>
 *   java releng/tools/javarelease/JavaUpgrade.java 25 --java /usr/lib/jvm/temurin-25-jdk
 *   flags: --no-tests, --yes, --dry-run (as the Release tool)
 * </pre>
 *
 * <h2>Why a tool</h2>
 * The number stands in eleven places Maven cannot fill in (the execution environment in both
 * {@code MANIFEST.MF}, {@code build.properties}, {@code .java-version} that CI's setup-java reads,
 * the docs), and the one way to know a raise works is to build with that JDK and start the oldest
 * supported Eclipse on it: Eclipse 4.34's {@code eclipse.ini} does not start on Java 24 or newer,
 * so Java 25 needs a newer oldest Eclipse first. Neither Dependabot nor the dependency tool moves
 * it: it is not a library's version but a promise to users.
 *
 * <h2>The steps</h2>
 * <ol>
 *   <li>The new release must be a long-term-support one and higher than today's
 *       ({@link JavaFiles#checkTarget}), and {@code --java} a JDK of exactly that release (its
 *       {@code release} file). The tool downloads no JDK: install one, and give its home.</li>
 *   <li>After y/N, the edits: {@code <java.release>}, every copy that {@code releng/version-copies.txt}
 *       lists under {@code [java.release]}, and the changelog entry ({@link JavaFiles#changelogEntry}).
 *       Only those files may change.</li>
 *   <li>The proof, on that JDK ({@code JAVA_HOME} and {@code PATH} point at it, so {@code ./mvnw}
 *       builds with it): the Eclipse tool's {@code smoke}, which builds the update site and
 *       installs it into the oldest and the newest pinned Eclipse started on that Java, then every
 *       test ({@code LikeCi --all}). A failure puts every file back.</li>
 *   <li>The commit <i>Raise the Java release to 25</i>.</li>
 * </ol>
 */
public final class JavaUpgradeTool {

    static final String TOOL = "releng/tools/javarelease/JavaUpgrade.java";
    private static final String USAGE = "Usage: java " + TOOL + " <release> --java <JDK home> [--no-tests] [--yes] [--dry-run]";

    private final Path root;
    private final Shell shell;
    private final Git git;
    private final Settings settings;
    private final Ask ask;
    private final int to;
    private final Path javaHome;
    private final boolean tests;

    private JavaUpgradeTool(Path root, Ask ask, int to, Path javaHome, boolean tests) {
        this.root = root;
        this.shell = new Shell(root);
        this.git = new Git(shell);
        this.settings = Settings.load(List.of(root.resolve(Settings.COMMON)), Map.of());
        this.ask = ask;
        this.to = to;
        this.javaHome = javaHome;
        this.tests = tests;
    }

    public static void main(String[] args) {
        Shell.requireSupportedShell("JavaUpgrade");
        try {
            Path root = Path.of("").toAbsolutePath();
            if (!Files.isRegularFile(root.resolve(TOOL))) {
                throw new ToolFailure("run this from the repository's root folder: java " + TOOL);
            }
            Integer release = null;
            String java = null;
            boolean tests = true;
            Ask.Mode mode = Ask.Mode.ASK;
            List<String> rerun = new ArrayList<>(List.of("java", TOOL));
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--no-tests" -> tests = false;
                    case "--yes" -> mode = mode == Ask.Mode.DRY_RUN ? mode : Ask.Mode.YES;
                    case "--dry-run" -> mode = Ask.Mode.DRY_RUN;
                    case "--java" -> {
                        if (java != null) {
                            throw new ToolFailure("--java is given twice\n" + USAGE);
                        }
                        if (i + 1 >= args.length) {
                            throw new ToolFailure("--java needs a JDK's home folder\n" + USAGE);
                        }
                        java = args[++i];
                        rerun.add(a);
                        a = java;
                    }
                    default -> {
                        if (a.startsWith("-") || release != null || !a.matches("\\d{1,3}")) {
                            throw new ToolFailure("unknown argument " + a + "\n" + USAGE);
                        }
                        release = Integer.parseInt(a);
                    }
                }
                if (!a.equals("--yes") && !a.equals("--dry-run")) {
                    rerun.add(a);
                }
            }
            if (release == null || java == null) {
                throw new ToolFailure("name the Java release and a JDK of it, e.g. 25 --java /usr/lib/jvm/temurin-25-jdk\n" + USAGE);
            }
            rerun.add("--yes");
            // Shell.display quotes a word with a space for a POSIX shell: /opt/my jdk (or Git Bash's
            // C:\Program Files\Eclipse Adoptium\jdk-25) stays one argument when the line is pasted back.
            new JavaUpgradeTool(root, new Ask(mode, Shell.display(rerun)), release, Path.of(java).toAbsolutePath(), tests).run();
        } catch (ToolFailure f) {
            System.err.println();
            System.err.println("JavaUpgrade: " + f.getMessage());
            System.exit(1);
        }
    }

    private void run() {
        git.checkWorkBranch("raise the Java release", Version.inPom(read("pom.xml")));
        if (!git.isClean()) {
            throw new ToolFailure("the working tree has changes (git status); commit or stash them first");
        }
        int from = JavaFiles.releaseIn(read("pom.xml"));
        JavaFiles.checkTarget(from, to);
        int jdk = jdkRelease(javaHome);
        if (jdk != to) {
            throw new ToolFailure(javaHome + " is a JDK " + jdk + ", not " + to + ": name a JDK " + to + "'s home with --java");
        }
        System.out.println("ok: Java " + to + " is a long-term-support release, higher than " + from + "; building with " + javaHome);
        List<VersionCopies.Copy> copies = VersionCopies.parse(read(VersionCopies.FILE)).get(JavaFiles.PROPERTY);
        if (copies == null || copies.isEmpty()) {
            throw new ToolFailure(VersionCopies.FILE + " has no [" + JavaFiles.PROPERTY + "] section");
        }
        Set<String> files = new LinkedHashSet<>(List.of("pom.xml", Changelog.FILE));
        copies.forEach(c -> files.add(c.file()));

        // System.getenv(name) ignores case on Windows, where the variable is often "Path"; the map
        // System.getenv() returns does not, and would give no PATH at all there.
        String path = System.getenv("PATH");
        Map<String, String> onJdk = Map.of("JAVA_HOME", javaHome.toString(),
                "PATH", javaHome.resolve("bin") + (path == null || path.isEmpty() ? "" : File.pathSeparator + path));
        List<String> smoke = Shell.java("releng/tools/eclipse/EclipseUpgrade.java", "smoke", "--java", javaHome.toString());
        List<String> likeCi = Shell.java("releng/tools/ci/LikeCi.java", "--all");
        List<String> plan = new ArrayList<>();
        plan.add("pom.xml: <java.release>" + from + "</java.release> to " + to);
        for (VersionCopies.Copy c : copies) {
            plan.add(c.file() + ": " + c.pattern() + ", " + from + " to " + to);
        }
        plan.add(Changelog.FILE + ": under Unreleased, Changed: " + JavaFiles.changelogEntry(to));
        if (tests) {
            String on = "with JAVA_HOME " + Shell.display(List.of(javaHome.toString())) + ": ";
            plan.add(on + Shell.display(smoke));
            plan.add(on + Shell.display(likeCi));
        } else {
            plan.add("no smoke install and no tests (--no-tests)");
        }
        String message = "Raise the Java release to " + to;
        String trailer = settings.get("commit.trailer", "");
        boolean committed = git.editAndCommit(ask, message + " (from " + from + "), then commit", plan,
                trailer.isEmpty() ? message : message + "\n\n" + trailer, () -> {
                    edit(from, copies);
                    List<String> changed = git.out("diff", "--name-only", "HEAD").lines().toList();
                    List<String> others = changed.stream().filter(f -> !files.contains(f)).toList();
                    if (!others.isEmpty()) {
                        throw new ToolFailure("files other than the Java release's copies changed: " + others);
                    }
                    if (tests) {
                        prove(smoke, likeCi, onJdk, from);
                    }
                    return changed;
                });
        if (!committed) {
            return; // a dry run
        }
        System.out.println();
        System.out.println("What stays yours (docs/UPGRADING.md, \"JDK / Java release\"):");
        System.out.println("  - On macOS and Windows, with a JDK " + to + ": java releng/tools/ci/LikeCi.java --all, then java "
                + "releng/tools/eclipse/EclipseUpgrade.java smoke --java <its home>");
        System.out.println("  - In the IDE: a JDK " + to + " under Preferences > Java > Installed JREs > Execution Environments, then Maven > Update Project");
        System.out.println("  - If smoke skipped an Eclipse (no Platform for this machine), install into it on another machine:"
                + " CI's smoke job does on Linux after the push");
        System.out.println("  - README says the Eclipse packages bring their own Java " + to + "+: check that the oldest supported"
                + " release's packages do");
        System.out.println("  - The tools are compiled at <java.release> in tests/unit and promise Java 22 to run: keep them to Java 22's"
                + " APIs (release/Release.java says why)");
        System.out.println("  - Notes about one Java that are not copies (the smoke job's comment in .github/workflows/update-site.yml,"
                + " local.properties.example): read them again");
        System.out.println("  - The Marketplace listing's Minimum Java Version: java releng/tools/marketplace/Marketplace.java shows the change");
    }

    /** The edits: the property, each listed copy, the changelog entry (replacing one from an earlier raise before a release). */
    private void edit(int from, List<VersionCopies.Copy> copies) {
        write("pom.xml", JavaFiles.withRelease(read("pom.xml"), to));
        for (VersionCopies.Copy c : copies) {
            String text = read(c.file());
            List<String> found = VersionCopies.versionsIn(text, c);
            if (found.isEmpty()) {
                throw new ToolFailure(c.file() + ": nothing matches " + c.pattern() + " (" + VersionCopies.FILE + ")");
            }
            if (found.stream().anyMatch(v -> !v.equals(Integer.toString(from)))) {
                throw new ToolFailure(c.file() + ": " + c.pattern() + " finds " + found + ", not " + from + "; run VersionConsistencyTest first");
            }
            write(c.file(), VersionCopies.rewrite(text, c, Integer.toString(to)));
        }
        Changelog.Removed earlier = Changelog.withoutEntry(read(Changelog.FILE), JavaFiles.ENTRY);
        write(Changelog.FILE, Changelog.withChangedEntry(earlier.changelog(), JavaFiles.changelogEntry(to)));
        System.out.println("ok: Java " + to + " in pom.xml, " + copies.size() + " copies and " + Changelog.FILE);
    }

    /** The smoke install into both pinned Eclipses, then every test, each on the new JDK. */
    private void prove(List<String> smoke, List<String> likeCi, Map<String, String> onJdk, int from) {
        System.out.println();
        System.out.println(Shell.display(smoke));
        if (shell.show(smoke, onJdk) != 0) {
            throw new ToolFailure("the smoke install on Java " + to + " failed (see above). If the oldest pinned Eclipse does not start on"
                    + " Java " + to + " (4.34 refuses 24 and newer), raise it first: java releng/tools/eclipse/EclipseUpgrade.java oldest"
                    + " <release>, on the Java " + from + " still in use");
        }
        System.out.println(Shell.display(likeCi));
        if (shell.show(likeCi, onJdk) != 0) {
            throw new ToolFailure("the tests failed on Java " + to + " (see above); a JaCoCo or Tycho too old for it is a common cause"
                    + " (docs/UPGRADING.md, \"Build tools\")");
        }
        System.out.println("ok: the smoke install and every test passed on Java " + to);
    }

    /** A JDK home's feature release, from its {@code release} file; fails for a folder that is not a JDK's home. */
    private static int jdkRelease(Path home) {
        Path file = home.resolve("release");
        if (!Files.isRegularFile(file) || !Files.isRegularFile(home.resolve("bin").resolve(Shell.WINDOWS ? "javac.exe" : "javac"))) {
            throw new ToolFailure(home + " is not a JDK's home (it needs a release file and bin/javac); on macOS it is …/Contents/Home");
        }
        try {
            OptionalInt release = JavaFiles.featureRelease(Files.readString(file));
            return release.orElseThrow(() -> new ToolFailure(file + " has no JAVA_VERSION line"));
        } catch (IOException e) {
            throw new ToolFailure("cannot read " + file + ": " + e.getMessage());
        }
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
}
