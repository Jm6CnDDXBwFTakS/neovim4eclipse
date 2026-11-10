// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import common.Ask;
import common.Folders;
import common.Git;
import common.Http;
import common.Settings;
import common.Shell;
import common.ToolFailure;
import common.Version;

/**
 * The Maven tool: moves the build to the newest Maven on its line and the newest Maven wrapper
 * (docs/UPGRADING.md, "Build tools").
 * <pre>
 *   java releng/tools/maven/MavenUpgrade.java                    both to their newest release
 *   java releng/tools/maven/MavenUpgrade.java --maven 3.9.17     a given Maven (on maven.line)
 *   java releng/tools/maven/MavenUpgrade.java --wrapper 3.3.5    a given wrapper
 *   flags: --allow-young (a release under a week old), --no-tests, --yes, --dry-run (as the Release tool)
 * </pre>
 *
 * <h2>Why a tool, and not Dependabot</h2>
 * Dependabot updates the other build tools, but not these two: it runs {@code ./mvnw wrapper:wrapper}
 * on a copy of the repository holding only the build files, and Tycho, which reads every module's
 * {@code META-INF/MANIFEST.MF} before any goal, fails there ({@code .github/dependabot.yml} has the
 * details). Here the same goal runs in the real checkout.
 *
 * <h2>The steps</h2>
 * <ol>
 *   <li>The newest releases from Maven Central's {@code maven-metadata.xml}: Maven on
 *       {@code maven.line} ({@code releng/tools/maven/settings.properties}, 3.9 until Tycho
 *       supports Maven 4), the wrapper of any version. Nothing to do if both are in use.</li>
 *   <li>Each new one must be a week old ({@code Last-Modified} of its archive on Central), as the
 *       Neovim tool asks of Neovim: a broken release is usually replaced within days.</li>
 *   <li>The archives downloaded and checked ({@link Http#download}): Maven's against Central's
 *       SHA-512, the wrapper's scripts against its SHA-1 (Central has no SHA-512 for them); both
 *       against their PGP signatures when {@code gpg} is installed ({@link Signatures}). The
 *       SHA-256 of Maven's archive is what {@code ./mvnw} checks before it runs that Maven.</li>
 *   <li>A new wrapper, after y/N: {@code maven-wrapper-plugin} at that version writes the scripts
 *       and the settings file, Maven's own way ({@link MavenFiles#wrapperGoal}). The tool then
 *       checks that the scripts are the ones in the checked archive and that the file names
 *       Central's archive and its SHA-256, and commits <i>Move to Maven Wrapper 3.3.5</i>.</li>
 *   <li>A new Maven, after y/N: the same goal with the new Maven and its SHA-256, the same checks,
 *       every test ({@code LikeCi --all}, which downloads the new Maven through {@code ./mvnw}),
 *       and the commit <i>Move to Maven 3.9.17</i>.</li>
 * </ol>
 * One commit per upgrade (AGENTS.md, "one upgrade per commit"), one test run after both: when
 * both move, the tests run in the Maven step, so the wrapper's commit is tested with them.
 *
 * <p>No changelog entry: what users install does not change, only how it is built.
 */
public final class MavenUpgradeTool {

    static final String TOOL = "releng/tools/maven/MavenUpgrade.java";
    /** The files a move may change: the scripts and the settings file. */
    private static final List<String> WRAPPER_FILES = Stream.concat(MavenFiles.SCRIPTS.stream(), Stream.of(MavenFiles.PROPERTIES)).toList();
    private static final String USAGE = "Usage: java " + TOOL + " [--maven <version>] [--wrapper <version>]"
            + " [--allow-young] [--no-tests] [--yes] [--dry-run]";

    private final Path root;
    private final Shell shell;
    private final Git git;
    private final Settings settings;
    private final Ask ask;
    private final Version mavenGiven;
    private final Version wrapperGiven;
    private final boolean allowYoung;
    private final boolean tests;

    private MavenUpgradeTool(Path root, Ask ask, Version maven, Version wrapper, boolean allowYoung, boolean tests) {
        this.root = root;
        this.shell = new Shell(root);
        this.git = new Git(shell);
        this.settings = Settings.load(List.of(root.resolve(Settings.COMMON), root.resolve("releng/tools/maven")), Map.of());
        this.ask = ask;
        this.mavenGiven = maven;
        this.wrapperGiven = wrapper;
        this.allowYoung = allowYoung;
        this.tests = tests;
    }

    public static void main(String[] args) {
        try {
            Path root = Path.of("").toAbsolutePath();
            if (!Files.isRegularFile(root.resolve(TOOL))) {
                throw new ToolFailure("run this from the repository's root folder: java " + TOOL);
            }
            Version maven = null;
            Version wrapper = null;
            boolean allowYoung = false;
            boolean tests = true;
            Ask.Mode mode = Ask.Mode.ASK;
            List<String> rerun = new ArrayList<>(List.of("java", TOOL));
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--allow-young" -> allowYoung = true;
                    case "--no-tests" -> tests = false;
                    case "--yes" -> mode = mode == Ask.Mode.DRY_RUN ? mode : Ask.Mode.YES;
                    case "--dry-run" -> mode = Ask.Mode.DRY_RUN;
                    case "--maven", "--wrapper" -> {
                        if (i + 1 >= args.length) {
                            throw new ToolFailure(a + " needs a version such as 3.9.17\n" + USAGE);
                        }
                        Version v = Version.parse(args[++i]);
                        if (a.equals("--maven")) {
                            maven = v;
                        } else {
                            wrapper = v;
                        }
                        rerun.add(a);
                        a = v.toString();
                    }
                    default -> throw new ToolFailure("unknown argument " + a + "\n" + USAGE);
                }
                if (!a.equals("--yes") && !a.equals("--dry-run")) {
                    rerun.add(a);
                }
            }
            rerun.add("--yes");
            new MavenUpgradeTool(root, new Ask(mode, String.join(" ", rerun)), maven, wrapper, allowYoung, tests).run();
        } catch (ToolFailure f) {
            System.err.println();
            System.err.println("MavenUpgrade: " + f.getMessage());
            System.exit(1);
        }
    }

    /** The move of one of the two: which version, from which. */
    private record Move(String name, Version from, Version to) {
        boolean moves() {
            return to.compareTo(from) > 0;
        }
    }

    private void run() {
        git.checkWorkBranch("move Maven", Version.inPom(read("pom.xml")));
        if (!git.isClean()) {
            throw new ToolFailure("the working tree has changes (git status); commit or stash them first");
        }
        MavenFiles.WrapperSettings now = MavenFiles.settings(read(MavenFiles.PROPERTIES));
        if (!now.type().equals("only-script")) {
            // The goal writes only-script wrappers: scripts that download Maven, no jar in the repository.
            throw new ToolFailure(MavenFiles.PROPERTIES + " has distributionType=" + now.type() + "; the tool moves only-script wrappers");
        }
        String line = settings.get("maven.line");
        Move maven = new Move("Maven", now.maven(), target("Maven", mavenGiven, now.maven(),
                MavenFiles.releases(Http.text(MavenFiles.MAVEN + "maven-metadata.xml")), Optional.of(line)));
        Move wrapper = new Move("Maven Wrapper", now.wrapper(), target("Maven Wrapper", wrapperGiven, now.wrapper(),
                MavenFiles.releases(Http.text(MavenFiles.WRAPPER + "maven-metadata.xml")), Optional.empty()));
        for (Move m : List.of(wrapper, maven)) {
            boolean given = (m == maven ? mavenGiven : wrapperGiven) != null;
            System.out.println(m.moves() ? m.name() + " " + m.to() + (given ? " was asked for" : " is out") + "; in use: " + m.from()
                    : "ok: " + m.name() + " " + m.from() + (given ? " is in use already" : " is the newest" + (m == maven ? " " + line + ".x" : "")));
        }
        if (!maven.moves() && !wrapper.moves()) {
            return;
        }
        for (Move m : List.of(wrapper, maven)) {
            if (m.moves()) {
                checkAge(m, m == maven ? MavenFiles.mavenZip(m.to()) : MavenFiles.wrapperZip(m.to()));
            }
        }

        Path work = root.resolve("target/maven-upgrade");
        Optional<Signatures> pgp = Signatures.prepare(shell, work, settings.get("maven.keys"));
        if (pgp.isEmpty()) {
            System.out.println("  skipped: the PGP check (no gpg on PATH); the downloads are checked against Central's hashes only");
        }
        Path scripts = wrapper.moves() ? wrapperScripts(wrapper.to(), work, pgp) : null;
        String sha256 = maven.moves() ? mavenSha256(maven.to(), work, pgp) : now.sha256();

        boolean wrapperCommitted = false;
        if (wrapper.moves()) {
            boolean testsHere = tests && !maven.moves();
            MavenFiles.WrapperSettings expected = new MavenFiles.WrapperSettings(wrapper.to(), now.type(), now.distributionUrl(), now.sha256());
            List<String> plan = List.of(
                    Shell.display(Shell.maven(MavenFiles.wrapperGoal(wrapper.to(), now.maven(), now.sha256()).toArray(String[]::new))),
                    "check that mvnw and mvnw.cmd are the ones in the checked archive, and " + MavenFiles.PROPERTIES,
                    testsHere ? "java releng/tools/ci/LikeCi.java --all" : tests ? "the tests run after the Maven step" : "no tests (--no-tests)");
            wrapperCommitted = commit("Move to Maven Wrapper " + wrapper.to() + " (from " + wrapper.from() + "), then commit", plan,
                    "Move to Maven Wrapper " + wrapper.to(), () -> {
                        writeWrapper(expected, scripts);
                        if (testsHere) {
                            runTests("Maven Wrapper " + wrapper.to());
                        }
                    });
            if (wrapperCommitted) {
                now = MavenFiles.settings(read(MavenFiles.PROPERTIES)); // the Maven step runs the new wrapper's plug-in
            } // a dry run: the Maven step is shown on today's wrapper
        }
        if (maven.moves()) {
            MavenFiles.WrapperSettings expected = new MavenFiles.WrapperSettings(now.wrapper(), now.type(),
                    MavenFiles.mavenZip(maven.to()), sha256);
            List<String> plan = List.of(
                    Shell.display(Shell.maven(MavenFiles.wrapperGoal(now.wrapper(), maven.to(), sha256).toArray(String[]::new))),
                    "check " + MavenFiles.PROPERTIES + ": " + expected.distributionUrl() + ", SHA-256 " + sha256,
                    tests ? "java releng/tools/ci/LikeCi.java --all, with Maven " + maven.to() : "no tests (--no-tests)");
            boolean alsoWrapper = wrapperCommitted; // a lambda needs a final copy
            try {
                commit("Move to Maven " + maven.to() + " (from " + maven.from() + "), then commit", plan, "Move to Maven " + maven.to(), () -> {
                    writeWrapper(expected, null); // null: the scripts must stay as they are, the wrapper does not move here
                    if (tests) {
                        runTests("Maven " + maven.to() + (alsoWrapper ? " and Maven Wrapper " + wrapper.to() : ""));
                    }
                });
            } catch (ToolFailure f) {
                if (!wrapperCommitted) {
                    throw f;
                }
                // The wrapper's commit is made and stays; its tests were to run in this step.
                throw new ToolFailure(f.getMessage() + "\nThe commit \"Move to Maven Wrapper " + wrapper.to() + "\" (" + git.head().substring(0, 7)
                        + ") is made, but no test ran with it" + (tests ? "" : " (--no-tests)") + ": run java releng/tools/ci/LikeCi.java --all,"
                        + " or take it back with git revert.");
            }
        }
        System.out.println();
        System.out.println("What stays yours: read the release notes (https://maven.apache.org/docs/history.html,"
                + " https://github.com/apache/maven-wrapper/releases), and on macOS and Windows run");
        System.out.println("  java releng/tools/ci/LikeCi.java --all");
    }

    /** The version to move to: the one given (which must exist, on the line, not older), or the newest. */
    private static Version target(String name, Version given, Version now, List<Version> releases, Optional<String> line) {
        if (given != null) {
            if (!releases.contains(given)) {
                throw new ToolFailure(name + " " + given + " is not a release on Maven Central (newest: " + releases.getLast() + ")");
            }
            if (line.isPresent() && !MavenFiles.isOn(given, line.get())) {
                throw new ToolFailure("Maven " + given + " is not on maven.line " + line.get()
                        + " (releng/tools/maven/settings.properties): change the line first, deliberately (docs/UPGRADING.md, \"Build tools\")");
            }
            if (given.compareTo(now) < 0) {
                throw new ToolFailure(name + " " + given + " is older than " + now + ", which is in use; the tool does not move back");
            }
            return given;
        }
        Optional<Version> newest = line.isPresent() ? MavenFiles.newestOn(releases, line.get()) : Optional.of(releases.getLast());
        return newest.filter(v -> v.compareTo(now) > 0).orElse(now);
    }

    private void checkAge(Move m, String zip) {
        Instant published = Http.lastModified(zip)
                .orElseThrow(() -> new ToolFailure("Maven Central gives no date for " + zip + "; the week-old rule cannot be checked"));
        if (MavenFiles.isYoung(published, Instant.now())) {
            if (!allowYoung) {
                throw new ToolFailure(m.name() + " " + m.to() + " came out " + published + ", less than a week ago; wait, or run"
                        + " with --allow-young if it fixes something the build needs now");
            }
            System.out.println("  note: " + m.name() + " " + m.to() + " is less than a week old (" + published + "); --allow-young");
        } else {
            System.out.println("ok: " + m.name() + " " + m.to() + " came out " + published + ", more than a week ago");
        }
    }

    /** The wrapper's archive, checked, unpacked into its own folder: the scripts the plug-in must write. */
    private Path wrapperScripts(Version v, Path work, Optional<Signatures> pgp) {
        String url = MavenFiles.wrapperZip(v);
        Path zip = Http.download(url, new Http.Digest("SHA-1", Http.digestIn(Http.text(url + ".sha1"), 40)), work.resolve("downloads"));
        pgp.ifPresent(s -> System.out.println("ok: " + zip.getFileName() + " is signed by " + s.verify(zip, url)));
        Path scripts = work.resolve("wrapper-" + v);
        Folders.deleteTree(scripts);
        Folders.unzip(zip, scripts);
        for (String script : MavenFiles.SCRIPTS) {
            if (!Files.isRegularFile(scripts.resolve(script))) {
                throw new ToolFailure(zip.getFileName() + " has no " + script);
            }
        }
        return scripts;
    }

    /** Maven's archive, checked; its SHA-256, which {@code ./mvnw} checks before it runs this Maven. */
    private String mavenSha256(Version v, Path work, Optional<Signatures> pgp) {
        String url = MavenFiles.mavenZip(v);
        Path zip = Http.download(url, new Http.Digest("SHA-512", Http.digestIn(Http.text(url + ".sha512"), 128)), work.resolve("downloads"));
        pgp.ifPresent(s -> System.out.println("ok: " + zip.getFileName() + " is signed by " + s.verify(zip, url)));
        String sha256 = Http.hash(zip, "SHA-256");
        System.out.println("ok: " + zip.getFileName() + " has SHA-256 " + sha256);
        return sha256;
    }

    /**
     * Runs the plug-in for {@code expected}, then checks what it wrote: the settings file exactly
     * ({@link MavenFiles#difference}), and the scripts against {@code scripts} (the checked
     * archive's) or, when that is {@code null}, unchanged.
     */
    private void writeWrapper(MavenFiles.WrapperSettings expected, Path scripts) {
        System.out.println();
        List<String> goal = Shell.maven(MavenFiles.wrapperGoal(expected.wrapper(), expected.maven(), expected.sha256()).toArray(String[]::new));
        System.out.println(Shell.display(goal));
        if (shell.show(goal) != 0) {
            throw new ToolFailure("maven-wrapper-plugin " + expected.wrapper() + " failed (see above)");
        }
        Optional<String> wrong = MavenFiles.difference(read(MavenFiles.PROPERTIES), expected);
        if (wrong.isPresent()) {
            throw new ToolFailure(MavenFiles.PROPERTIES + " is not as expected: " + wrong.get()
                    + " (a mirror in ~/.m2/settings.xml? The file must name Maven Central, for every machine and CI)");
        }
        List<String> added = git.out("status", "--porcelain", "--untracked-files=all").lines()
                .filter(l -> l.startsWith("?? ")).map(l -> l.substring(3)).toList(); // target/ is ignored
        if (!added.isEmpty()) {
            throw new ToolFailure("maven-wrapper-plugin added files the wrapper does not use: " + added);
        }
        for (String script : MavenFiles.SCRIPTS) {
            if (scripts == null) {
                if (!git.out("diff", "--name-only", "--", script).isEmpty()) {
                    throw new ToolFailure(script + " changed, though the wrapper's version did not");
                }
            } else if (!sameBytes(root.resolve(script), scripts.resolve(script))) {
                throw new ToolFailure(script + " is not the one in the checked archive " + MavenFiles.wrapperZip(expected.wrapper()));
            }
        }
        System.out.println("ok: " + MavenFiles.PROPERTIES + " names Maven " + expected.maven() + " on Central, SHA-256 " + expected.sha256()
                + (scripts == null ? "" : "; mvnw and mvnw.cmd are the checked archive's"));
    }

    private static boolean sameBytes(Path a, Path b) {
        try {
            return Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(b));
        } catch (IOException e) {
            throw new ToolFailure("cannot compare " + a + " with " + b + ": " + e.getMessage());
        }
    }

    private void runTests(String what) {
        if (shell.show(Shell.java("releng/tools/ci/LikeCi.java", "--all")) != 0) {
            throw new ToolFailure("the tests failed with " + what + " (see above)");
        }
        System.out.println("ok: every test passed with " + what);
    }

    /** {@link Git#editAndCommit}: the edits, their checks and the commit after y/N; false if it was not made (--dry-run). */
    private boolean commit(String what, List<String> plan, String message, Runnable edit) {
        String trailer = settings.get("commit.trailer", "");
        return git.editAndCommit(ask, what, plan, trailer.isEmpty() ? message : message + "\n\n" + trailer, () -> {
            edit.run();
            // Against HEAD: staged and unstaged changes alike, since the commit takes the whole index.
            List<String> changed = git.out("diff", "--name-only", "HEAD").lines().toList();
            // Only the wrapper's own files may change: the plug-in runs code from Central (or a
            // mirror), and anything else it touched would otherwise be committed with them.
            List<String> others = changed.stream().filter(f -> !WRAPPER_FILES.contains(f)).toList();
            if (!others.isEmpty()) {
                throw new ToolFailure("files other than the wrapper's changed: " + others);
            }
            return changed;
        });
    }

    private String read(String file) {
        try {
            return Files.readString(root.resolve(file));
        } catch (IOException e) {
            throw new ToolFailure("cannot read " + file + ": " + e.getMessage());
        }
    }
}
