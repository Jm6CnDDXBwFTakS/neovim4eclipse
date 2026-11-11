// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package release;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import checks.InstallCheckTool;

import common.Ask;
import common.EclipseRelease;
import common.Folders;
import common.Git;
import common.GitHub;
import common.Http;
import common.Settings;
import common.Shell;
import common.ToolFailure;
import common.UpdateSite;
import common.Version;

import dependencies.DependabotRules;
import dependencies.Updates;
import eclipse.EclipseFiles;
import maven.MavenFiles;

/**
 * The Release tool: CONTRIBUTING.md's "Releasing" as steps a person runs, each of which checks
 * before it acts and asks before every side effect ({@link Ask}). Started through
 * {@link Release} (which explains why); every step reads git and GitHub afresh, so any step can be
 * run again after a failure, and {@code go} goes on from wherever the release stands.
 *
 * <h2>The steps, for release 0.2.3 on {@code release/0.2.3}</h2>
 * <ol>
 *   <li><b>check</b>: on {@code release/0.2.3}, clean, not behind origin, the POM at
 *       {@code 0.2.3-SNAPSHOT}, changes listed under <i>Unreleased</i>. The six pinned Neovim
 *       archives still match their hashes ({@code UpdateNeovimDownloads.java --check}, as the
 *       Release workflow's first job), with a line when Neovim has a newer release, and one when
 *       Eclipse has a release newer than the smoke job's newest ({@code EclipseUpgrade.java}), and
 *       one when Maven or its wrapper has ({@code MavenUpgrade.java}), and one when a library, a
 *       Maven plug-in or an action has ({@code Dependencies.java}); the manual checks due
 *       ({@code ManualChecks.java due}). Then every test on this machine ({@code LikeCi.java --all}):
 *       the commit that passes is written to {@code target/release-tool/tested}, and {@code promote} wants it. It prints the same test
 *       command for macOS and Windows, a reminder only: CI's macOS and Windows jobs are the gate.</li>
 *   <li><b>prepare</b>: dates the changelog section, names the version in the README's status line
 *       ({@link ReleaseFiles}), and commits that.</li>
 *   <li><b>promote</b>: pushes the branch; pushes it to {@code rehearse/0.2.3} as well, which starts
 *       the Release rehearsal (build at the release version, signing with a throwaway key, both
 *       update sites, installs into Eclipse), watches it, and deletes that branch. With
 *       {@code --ci-on-branch}, also a Build run on the release branch. Then fast-forwards
 *       {@code main} to the branch and watches the Build that starts there: that run is the
 *       release's test run, which the Release workflow reuses instead of testing again.</li>
 *   <li><b>tag</b>: tags {@code main} as {@code v0.2.3} and pushes the tag, which starts the Release
 *       workflow, and watches it to the end (it publishes the update site too). Then checks the
 *       outcome as a user would meet it: the run reused the Build on {@code main}, the GitHub
 *       Release has the ZIP and its {@code .asc}, both signatures verify
 *       ({@code releng/verify-zip.sh}, {@code releng/verify-signatures.sh}), and the public update
 *       site lists the version.</li>
 *   <li><b>next</b>: cuts {@code release/0.2.4} from {@code main}, sets its version
 *       ({@code 0.2.4-SNAPSHOT}), commits and pushes it; deletes {@code release/0.2.3} and the
 *       {@code fix/}, {@code feature/} and {@code rehearse/} branches already in {@code main}.</li>
 * </ol>
 *
 * <h2>When a run fails</h2>
 * The tool prints the failed jobs' last log lines and the commands that recover: rerun the failed
 * jobs (a runner hiccup), or fix the cause on the release branch and run {@code promote} again.
 * It never deletes a tag, a release or a branch that is not finished with, and never forces a
 * push; when a tag must move, it prints the commands for a person to run.
 */
public final class ReleaseTool {

    static final String TOOL = "releng/tools/release/Release.java";
    static final String INSTALL_CHECK = "releng/tools/checks/InstallCheck.java";
    static final String NEOVIM_PINS = "plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/src/io/github/jm6cnddxbwftaks/neovim4eclipse/util/neovim-downloads.properties";
    private static final int LOG_LINES = 40;

    private final Path root;
    private final Options options;
    private final Settings settings;
    private final Shell shell;
    private final Git git;
    private final GitHub gh;
    private Ask ask;

    ReleaseTool(Path root, Options options) {
        this.root = root;
        this.options = options;
        this.settings = Settings.load(List.of(root.resolve(Settings.COMMON), root.resolve("releng/tools/release")), Map.of());
        this.shell = new Shell(root);
        this.git = new Git(shell);
        this.gh = new GitHub(shell);
        this.ask = new Ask(options.mode(), options.rerunWithYes(options.step()));
    }

    public static void main(String[] args) {
        try {
            Path root = Path.of("").toAbsolutePath();
            if (!Files.isRegularFile(root.resolve(TOOL))) {
                throw new ToolFailure("run this from the repository's root folder: java " + TOOL);
            }
            new ReleaseTool(root, Options.parse(args)).run();
        } catch (ToolFailure f) {
            System.err.println();
            System.err.println("Release: " + f.getMessage());
            System.exit(1);
        }
    }

    private void run() {
        switch (options.step()) {
            case "status" -> status();
            case "go" -> go();
            default -> step(options.step());
        }
    }

    private void step(String name) {
        ask = new Ask(options.mode(), options.rerunWithYes(name));
        System.out.println("== " + name + (options.mode() == Ask.Mode.DRY_RUN ? " (dry run)" : ""));
        Version v = pomVersion(); // before the step: next switches to the next release's branch
        switch (name) {
            case "check" -> check();
            case "prepare" -> prepare();
            case "promote" -> promote();
            case "tag" -> tag();
            case "next" -> next();
            default -> throw new ToolFailure("unknown step " + name);
        }
        // go goes on by itself; a dry run changed nothing, so its next step would be this one.
        if (!inGo && options.mode() != Ask.Mode.DRY_RUN) {
            System.out.println();
            System.out.println(stateAsFetched(v).nextLine(TOOL));
        }
    }

    /** Set while {@link #go} runs the steps, which then do not each print the next one. */
    private boolean inGo;

    // ---------------------------------------------------------------- status and go

    /** The facts behind {@link ReleaseState}, read now. Fetches first, so that origin's side is current. */
    private ReleaseState state(Version v) {
        git.fetch();
        return stateAsFetched(v);
    }

    /**
     * {@link #state} without the fetch, for the line after a step: the step has just fetched, and
     * its own pushes moved the remote-tracking branches, so origin's side is current already.
     */
    private ReleaseState stateAsFetched(Version v) {
        String head = git.head();
        boolean released = false;
        boolean tagged = remoteTagCommit(v.tag()) != null;
        if (tagged && Shell.onPath("gh")) {
            List<String> assets = gh.releaseAssets(v.tag());
            released = assets.contains(zipName(v)) && assets.contains(zipName(v) + ".asc");
        }
        return new ReleaseState(v, testsCover(head), ReleaseFiles.hasRelease(read("CHANGELOG.md"), v),
                head.equals(git.commitOf("origin/main")), tagged, released,
                git.commitOf("origin/" + nextVersion(v).branch()) != null);
    }

    /** Whether {@code InstallCheck} passed for {@code v} on this machine ({@code checks.InstallCheckTool.RECORD}). */
    private boolean installChecked(Version v) {
        Path record = root.resolve(InstallCheckTool.RECORD);
        try {
            return Files.isRegularFile(record) && InstallCheckTool.recorded(Files.readString(record)).equals(Optional.of(v));
        } catch (IOException e) {
            return false;
        }
    }

    private void status() {
        Version v = pomVersion();
        ReleaseState s = state(v);
        String head = git.head();
        String branch = git.branch();
        System.out.println("Release " + v + " on " + (branch.isEmpty() ? "a detached HEAD" : branch) + " at " + head.substring(0, 7));
        if (!branch.equals(v.branch())) {
            System.out.println("  note: the release is made from " + v.branch());
        }
        row("working tree", git.isClean() ? "clean" : "has changes");
        String upstream = git.commitOf("origin/" + v.branch());
        row("origin", upstream == null ? v.branch() + " not pushed" : aheadBehind(upstream, head));
        String changelog = read("CHANGELOG.md");
        row("changelog", s.prepared() ? "section " + v + " dated"
                : ReleaseFiles.unreleasedHasEntries(changelog) ? "changes under Unreleased, not dated" : "nothing under Unreleased");
        String tested = testedCommit();
        row("local tests", s.tested() ? "passed (LikeCi " + settings.get("tests") + ") at " + short7(tested)
                : tested.isEmpty() ? "not run for this release" : "passed at " + short7(tested) + ", not this commit");
        row("main", s.onMain() ? "at this commit" : "not at this commit");
        row(v.tag(), s.tagged() ? "on origin" : "not made");
        row("GitHub Release", s.released() ? "has the ZIP and its signature" : "none yet");
        row("install check", installChecked(v) ? "passed for " + v + " (InstallCheck)"
                : s.released() ? "not run for " + v + ": java " + INSTALL_CHECK + " " + v + " (recommended, not required)" : "after tag");
        row("next branch", nextVersion(v).branch() + (s.nextCut() ? " exists" : " not cut"));
        System.out.println();
        System.out.println(s.nextLine(TOOL) + (s.nextStep().equals("done") ? "" : "   (or go: every step from there)"));
    }

    private void go() {
        Version v = pomVersion();
        String first = state(v).nextStep();
        if (first.equals("done")) {
            System.out.println("Release " + v + " is done.");
            return;
        }
        List<String> steps = Options.STEPS.subList(Options.STEPS.indexOf(first), Options.STEPS.size());
        if (options.mode() == Ask.Mode.DRY_RUN) {
            // A dry run changes nothing, so the steps after the first would find their start missing.
            step(first);
            System.out.println();
            System.out.println("[dry run] then: " + String.join(", ", steps.subList(1, steps.size())));
            return;
        }
        inGo = true;
        for (String s : steps) {
            step(s);
            System.out.println();
        }
        inGo = false;
        System.out.println(stateAsFetched(v).nextLine(TOOL));
    }

    // ---------------------------------------------------------------- check

    private void check() {
        Version v = requireReleaseBranch();
        requireClean();
        git.fetch();
        String head = git.head();
        String upstream = git.commitOf("origin/" + v.branch());
        if (upstream != null && !git.isAncestor(upstream, head)) {
            throw new ToolFailure("origin/" + v.branch() + " has commits this branch lacks: git merge --ff-only origin/" + v.branch());
        }
        ok("on " + v.branch() + ", clean, " + (upstream == null ? "not pushed yet" : aheadBehind(upstream, head)));

        String changelog = read("CHANGELOG.md");
        if (!ReleaseFiles.hasRelease(changelog, v) && !ReleaseFiles.unreleasedHasEntries(changelog)) {
            throw new ToolFailure("CHANGELOG.md lists nothing under " + ReleaseFiles.UNRELEASED + "; add the release's changes there");
        }
        ok("CHANGELOG.md has the release's changes");

        System.out.println();
        checkNeovimPins();
        newerNeovim();
        newerEclipse();
        newerMaven();
        newerDependencies();

        System.out.println();
        System.out.println("Manual checks due for this release (docs/MANUAL_CHECKS.md):");
        if (shell.show(Shell.java("releng/tools/checks/ManualChecks.java", "due")) != 0) {
            throw new ToolFailure("ManualChecks.java due failed (see above)");
        }

        System.out.println();
        String tests = settings.get("tests");
        if (options.noTests()) {
            System.out.println("Local tests skipped (--no-tests); promote will want them.");
        } else if (options.mode() == Ask.Mode.DRY_RUN) {
            System.out.println("[dry run] would run: java releng/tools/ci/LikeCi.java " + tests);
        } else {
            List<String> likeCi = Shell.java("releng/tools/ci/LikeCi.java", tests.isEmpty() ? new String[0] : tests.split("\\s+"));
            if (shell.show(likeCi) != 0) {
                throw new ToolFailure("the local tests failed (see above); fix them on " + v.branch() + " and run check again");
            }
            writeTested(head);
            ok("the local tests passed at " + head.substring(0, 7) + " (recorded in target/release-tool/tested)");
        }
        System.out.println();
        System.out.println("On macOS and Windows, test the same commit once it is pushed (CI's macOS and Windows jobs are the gate):");
        System.out.println("  git fetch origin && git switch --detach " + head.substring(0, 12));
        System.out.println("  java releng/tools/ci/LikeCi.java " + tests);
    }

    /** How long a passed check of the pinned archives counts for the same pins. */
    private static final Duration PINS_CHECK_VALID = Duration.ofHours(24);

    /**
     * Checks that the six pinned Neovim archives on GitHub still match their sizes and hashes
     * ({@code UpdateNeovimDownloads.java --check}, which downloads each one in full), unless that
     * check passed for the same pins in the last day: {@code check} is often run several times
     * for one release, after each fix, and the archives of one pin do not change. The record is
     * {@code target/release-tool/neovim-checked} (the pins file's SHA-256 and the time); delete it
     * to check again. The Release workflow's first job checks the archives anew at the tag anyway.
     */
    private void checkNeovimPins() {
        Path record = root.resolve("target/release-tool/neovim-checked");
        String pins;
        try {
            pins = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(NEOVIM_PINS))));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new ToolFailure("cannot read " + NEOVIM_PINS + ": " + e.getMessage());
        }
        try {
            String[] last = Files.isRegularFile(record) ? Files.readString(record).strip().split(" ") : new String[0];
            if (last.length == 2 && last[0].equals(pins) && last[1].matches("\\d+")) {
                Instant when = Instant.ofEpochSecond(Long.parseLong(last[1]));
                if (Duration.between(when, Instant.now()).compareTo(PINS_CHECK_VALID) < 0) {
                    ok("the pinned Neovim archives matched at " + when + ", and the pins are unchanged (delete " + root.relativize(record)
                            + " to check again)");
                    return;
                }
            }
        } catch (IOException e) {
            // no usable record: check
        }
        if (shell.show(Shell.java("releng/UpdateNeovimDownloads.java", "--check")) != 0) {
            throw new ToolFailure("the pinned Neovim archives do not match their hashes (see above); docs/UPGRADING.md, \"Neovim\"");
        }
        try {
            Files.createDirectories(record.getParent());
            Files.writeString(record, pins + " " + Instant.now().getEpochSecond() + "\n");
        } catch (IOException e) {
            System.out.println("note: could not record the check in " + record + ": " + e.getMessage());
        }
        ok("the pinned Neovim archives match");
    }

    /**
     * Prints a line when Eclipse has a release newer than the newest the smoke job installs into
     * (the second entry of its matrix; docs/UPGRADING.md, "Eclipse release"). Not a blocker: the
     * release is still tested up to that one, and the move is its own commit.
     */
    private void newerEclipse() {
        List<EclipseRelease.SmokeEntry> smoke = EclipseRelease.smokeEntries(read(".github/workflows/update-site.yml"));
        if (smoke.size() != 2) {
            System.out.println("  (could not read the smoke job's newest Eclipse from update-site.yml)");
            return;
        }
        EclipseRelease tested = EclipseRelease.ofNumber(smoke.get(1).eclipse());
        EclipseRelease latest;
        try {
            latest = EclipseFiles.releaseOf(EclipseFiles.newestDrop(Http.text(EclipseFiles.DATA_JSON)));
        } catch (ToolFailure e) {
            System.out.println("  (could not read Eclipse's newest release: " + e.getMessage() + ")");
            return;
        }
        if (latest.compareTo(tested) > 0) {
            System.out.println("  note: Eclipse " + latest + " is out (tested up to " + tested + "): java "
                    + "releng/tools/eclipse/EclipseUpgrade.java newest (docs/UPGRADING.md, \"Eclipse release\"). Not a blocker.");
        } else {
            ok("Eclipse " + tested + ", the newest the smoke job installs into, is Eclipse's newest release");
        }
    }

    /**
     * Prints a line when Maven on its line ({@code maven.line} in releng/tools/maven/settings.properties)
     * or the Maven wrapper has a release newer than the build's ({@code .mvn/wrapper/maven-wrapper.properties}).
     */
    private void newerMaven() {
        MavenFiles.WrapperSettings now;
        Optional<Version> maven;
        Version wrapper;
        try {
            now = MavenFiles.settings(read(MavenFiles.PROPERTIES));
            String line = Settings.load(root.resolve("releng/tools/maven"), Map.of()).get("maven.line");
            maven = MavenFiles.newestOn(MavenFiles.releases(Http.text(MavenFiles.MAVEN + "maven-metadata.xml")), line);
            wrapper = MavenFiles.releases(Http.text(MavenFiles.WRAPPER + "maven-metadata.xml")).getLast();
        } catch (ToolFailure e) {
            System.out.println("  (could not read Maven's newest release: " + e.getMessage() + ")");
            return;
        }
        List<String> newer = new ArrayList<>();
        if (maven.isPresent() && maven.get().compareTo(now.maven()) > 0) {
            newer.add("Maven " + maven.get() + " (in use: " + now.maven() + ")");
        }
        if (wrapper.compareTo(now.wrapper()) > 0) {
            newer.add("Maven Wrapper " + wrapper + " (in use: " + now.wrapper() + ")");
        }
        if (newer.isEmpty()) {
            ok("Maven " + now.maven() + " and Maven Wrapper " + now.wrapper() + " are the newest");
        } else {
            System.out.println("  note: " + String.join(" and ", newer) + " is out; once a week old: java "
                    + "releng/tools/maven/MavenUpgrade.java (docs/UPGRADING.md, \"Build tools\"). Not a blocker.");
        }
    }

    /**
     * Prints a line with the updates the Dependency tool would make: libraries, Maven plug-ins and
     * GitHub Actions with a newer release, a week old, that {@code .github/dependabot.yml} allows
     * ({@link Updates}). Like the lines above it, a note, never a blocker.
     */
    private void newerDependencies() {
        Updates.Found found;
        try {
            Settings tool = Settings.load(List.of(root.resolve(Settings.COMMON), root.resolve("releng/tools/dependencies")), Map.of());
            found = new Updates(root, shell, tool, false, Set.of()).find();
        } catch (ToolFailure e) {
            System.out.println("  (could not look for dependency updates: " + e.getMessage() + ")");
            return;
        }
        if (found.count() == 0) {
            ok("the libraries, Maven plug-ins and GitHub Actions are the newest " + DependabotRules.FILE + " allows");
        } else {
            System.out.println("  note: " + found.count() + " dependency update" + (found.count() == 1 ? " is" : "s are") + " out ("
                    + found.summary() + "): java releng/tools/dependencies/Dependencies.java (docs/UPGRADING.md). Not a blocker.");
        }
    }

    /** Prints a line when Neovim has a release newer than the pinned one (docs/UPGRADING.md, "Following Neovim's releases"). */
    private void newerNeovim() {
        Properties pins = new Properties();
        try (InputStream in = Files.newInputStream(root.resolve(NEOVIM_PINS))) {
            pins.load(in);
        } catch (IOException e) {
            throw new ToolFailure("cannot read " + NEOVIM_PINS + ": " + e.getMessage());
        }
        Shell.Result r = Shell.onPath("gh") ? shell.capture(List.of("gh", "api", "repos/neovim/neovim/releases/latest", "--jq", ".tag_name"))
                : new Shell.Result(1, "", "");
        if (!r.ok() || !r.out().matches("v\\d+\\.\\d+\\.\\d+")) {
            System.out.println("  (could not ask GitHub for Neovim's newest release)");
            return;
        }
        Version pinned = Version.parse(pins.getProperty("version", "").strip());
        Version latest = Version.parse(r.out().substring(1));
        if (latest.compareTo(pinned) > 0) {
            System.out.println("  note: Neovim " + latest + " is out (pinned: " + pinned + "); once it is a week old: java "
                    + "releng/tools/neovim/NeovimUpgrade.java " + latest + " (docs/UPGRADING.md, \"Following Neovim's releases\"). Not a blocker.");
        } else {
            ok("Neovim " + pinned + " is Neovim's newest release");
        }
    }

    // ---------------------------------------------------------------- prepare

    private void prepare() {
        Version v = requireReleaseBranch();
        requireClean();
        String changelog = read("CHANGELOG.md");
        String readme = read("README.md");
        if (ReleaseFiles.hasRelease(changelog, v) && ReleaseFiles.readmeVersion(readme).equals(v.toString())) {
            ok("already prepared: CHANGELOG.md has " + v + ", the README names it");
            return;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        boolean dated = ReleaseFiles.hasRelease(changelog, v);
        String newChangelog = dated ? changelog : ReleaseFiles.datedChangelog(changelog, v, today);
        String newReadme = ReleaseFiles.readmeWithVersion(readme, v);
        List<String> plan = new ArrayList<>();
        if (!dated) {
            plan.add("CHANGELOG.md: ## [Unreleased] becomes ## [" + v + "] - " + today + ", under a new, empty ## [Unreleased]");
        }
        plan.add("README.md: **Status: " + ReleaseFiles.readmeVersion(readme) + ", … becomes **Status: " + v + ", …");
        String trailer = settings.get("commit.trailer", "");
        String message = "Release " + v + ": date its changelog section, name it in the README";
        git.editAndCommit(ask, "Date the changelog section and name " + v + " in the README, then commit", plan,
                trailer.isEmpty() ? message : message + "\n\n" + trailer, () -> {
                    write("CHANGELOG.md", newChangelog);
                    write("README.md", newReadme);
                    shell.show(List.of("git", "--no-pager", "diff", "--stat", "--", "CHANGELOG.md", "README.md"));
                    return ReleaseFiles.RELEASE_NOTES_FILES;
                });
    }

    // ---------------------------------------------------------------- promote

    private void promote() {
        Version v = requireReleaseBranch();
        requireClean();
        gh.requireReady();
        git.fetch();
        String head = git.head();
        if (!ReleaseFiles.hasRelease(read("CHANGELOG.md"), v)) {
            throw new ToolFailure("CHANGELOG.md has no dated section " + v + " yet: run prepare first");
        }
        if (!testsCover(head)) {
            if (!options.skipLocalTests()) {
                throw new ToolFailure("the local tests have not passed on this commit: run check first (or --skip-local-tests)");
            }
            System.out.println("note: no local test run of this commit (--skip-local-tests)");
        }
        String main = git.commitOf("origin/main");
        if (main != null && !git.isAncestor(main, head)) {
            throw new ToolFailure("origin/main has commits " + v.branch() + " lacks, so main cannot be fast-forwarded; rebase the branch on origin/main");
        }

        if (!head.equals(git.commitOf("origin/" + v.branch()))) {
            git.push(ask, "Push " + v.branch() + " to origin", v.branch());
        } else {
            ok(v.branch() + " is on origin");
        }

        if (options.rehearse()) {
            rehearse(v, head);
        } else {
            System.out.println("note: no rehearsal (--no-rehearsal)");
        }

        if (options.ciOnBranch()) {
            List<String> dispatch = List.of("gh", "workflow", "run", settings.get("build.workflow"), "--ref", v.branch());
            List<GitHub.Run> done = gh.runs(settings.get("build.workflow"), head, "workflow_dispatch", v.branch());
            if (done.stream().anyMatch(GitHub.Run::passed)) {
                ok("Build passed on " + v.branch() + " already");
            } else if (ask.confirm("Run the Build workflow on " + v.branch(), List.of(Shell.display(dispatch)))) {
                Set<Long> older = gh.runIds(settings.get("build.workflow"), head, "workflow_dispatch", v.branch());
                shell.out(dispatch);
                awaitAndWatch("the Build on " + v.branch(), settings.get("build.workflow"), head, "workflow_dispatch", v.branch(), older,
                        List.of("fix the cause on " + v.branch() + ", then run check and promote again"));
            }
        }

        Set<Long> olderOnMain = Set.of(); // main already at this commit: follow the run that is there
        if (!head.equals(main)) {
            olderOnMain = gh.runIds(settings.get("build.workflow"), head, "push", "main");
            git.push(ask, "Fast-forward main to " + v.branch() + " (" + head.substring(0, 7) + "); this starts the Build on main", head + ":refs/heads/main");
        } else {
            ok("main is at " + head.substring(0, 7));
        }
        if (options.mode() != Ask.Mode.DRY_RUN) {
            awaitAndWatch("the Build on main", settings.get("build.workflow"), head, "push", "main", olderOnMain,
                    List.of("rerun only the failed jobs, for a runner hiccup", "or fix the cause on " + v.branch()
                            + ", then run check and promote again (main moves on by fast-forward)"));
            ok("the Build passed on main: the release's test run");
        }
    }

    /** Pushes the commit to {@code rehearse/<v>}, watches the Release rehearsal, and deletes the branch. */
    private void rehearse(Version v, String head) {
        String branch = settings.get("rehearse.prefix") + v;
        String workflow = settings.get("rehearsal.workflow");
        String there = git.commitOf("origin/" + branch);
        if (there != null && !there.equals(head)) {
            git.push(ask, "Delete " + branch + " on origin, left from an earlier rehearsal of another commit", "--delete", branch);
            there = null;
        } else if (there != null) {
            // The branch stays when a rehearsal fails (the tool stops there). Its run of this commit
            // ended and failed, and nobody reran it (a rerun would be in progress): rehearse anew,
            // as running promote again promises, instead of reporting the same failure.
            List<GitHub.Run> runs = gh.runs(workflow, head, "push", branch);
            if (!runs.isEmpty() && runs.get(0).completed() && !runs.get(0).passed()) {
                git.push(ask, "Delete " + branch + " on origin, whose rehearsal of " + head.substring(0, 7) + " failed, to rehearse it again",
                        "--delete", branch);
                there = null;
            }
        }
        // The runs of this commit before the push: after a failed rehearsal, the same commit pushed
        // again starts a new run, and the old, failed one must not be the one followed.
        Set<Long> older = Set.of();
        if (there == null) {
            List<GitHub.Run> runs = gh.runs(workflow, head, "push", branch);
            if (!runs.isEmpty() && runs.get(0).passed()) {
                ok("the Release rehearsal passed on " + head.substring(0, 7) + " already");
                return;
            }
            older = new HashSet<>(runs.stream().map(GitHub.Run::id).toList());
            git.push(ask, "Push " + head.substring(0, 7) + " to " + branch + ", which starts the Release rehearsal", head + ":refs/heads/" + branch);
        }
        if (options.mode() == Ask.Mode.DRY_RUN) {
            System.out.println("[dry run] would watch the Release rehearsal, then delete " + branch);
            return;
        }
        awaitAndWatch("the Release rehearsal", workflow, head, "push", branch, older,
                List.of("rerun only the failed jobs, for a runner hiccup",
                        "or fix the cause on " + v.branch() + ", then run check and promote again (it pushes " + branch + " anew)",
                        "afterwards: git push origin --delete " + branch));
        ok("the Release rehearsal passed");
        git.push(ask, "Delete " + branch + " on origin (the rehearsal is done)", "--delete", branch);
    }

    // ---------------------------------------------------------------- tag

    private void tag() {
        Version v = pomVersion();
        gh.requireReady();
        git.fetch();
        String main = git.commitOf("origin/main");
        if (main == null) {
            throw new ToolFailure("no origin/main");
        }
        if (!ReleaseFiles.hasRelease(gitShow(main, "CHANGELOG.md"), v)) {
            throw new ToolFailure("main's CHANGELOG.md has no section " + v + ": run prepare and promote first");
        }
        String tagged = remoteTagCommit(v.tag());
        String local = git.commitOf("refs/tags/" + v.tag());
        if (tagged == null && local != null && !local.equals(main)) {
            // A tag made here earlier and never pushed (an attempt stopped halfway, a test): pushing it
            // would release that commit, not main.
            throw new ToolFailure("the local tag " + v.tag() + " is on " + short7(local) + ", not on main (" + short7(main)
                    + "), and origin has no such tag. Delete it if it is a leftover: git tag -d " + v.tag());
        }
        if (tagged != null && !tagged.equals(main)) {
            throw new ToolFailure(v.tag() + " is on " + tagged.substring(0, 7) + ", not on main (" + main.substring(0, 7) + "). "
                    + "If it must move (after deleting its GitHub Release, if one was made): git tag -f " + v.tag() + " " + main.substring(0, 12)
                    + " && git push -f origin " + v.tag() + ". The tool never moves a tag itself.");
        }
        String workflow = settings.get("release.workflow");
        Set<Long> older = Set.of(); // the tag on origin already: follow the run that is there
        if (tagged == null) {
            older = gh.runIds(workflow, main, "push", v.tag());
            // As the Release workflow counts them: a push or a Run workflow (workflow_dispatch) on main.
            String build = settings.get("build.workflow");
            boolean tested = gh.runs(build, main, "push", "main").stream().anyMatch(GitHub.Run::passed)
                    || gh.runs(build, main, "workflow_dispatch", "main").stream().anyMatch(GitHub.Run::passed);
            List<String> commands = List.of("git tag " + v.tag() + " " + main.substring(0, 12), "git push origin " + v.tag());
            if (!tested) {
                System.out.println("note: no green Build on main for " + main.substring(0, 7) + ", so the Release workflow runs every test itself");
            }
            if (ask.confirm("Tag main (" + main.substring(0, 7) + ") as " + v.tag() + " and push the tag; this starts the Release workflow", commands)) {
                if (local == null) {
                    git.out("tag", v.tag(), main);
                }
                if (shell.show(List.of("git", "push", "origin", "refs/tags/" + v.tag())) != 0) {
                    throw new ToolFailure("git push origin " + v.tag() + " failed (see above)");
                }
            }
        } else {
            ok(v.tag() + " is on main (" + main.substring(0, 7) + ")");
        }
        if (options.mode() == Ask.Mode.DRY_RUN) {
            System.out.println("[dry run] would watch the Release workflow, then verify the release and the update site");
            return;
        }
        GitHub.Run run = awaitAndWatch("the Release workflow", workflow, main, "push", v.tag(), older,
                List.of("rerun only the failed jobs, for a runner hiccup; if only the update site failed, the release is out:"
                        + " fix the cause and rebuild the site (Update site workflow, Run workflow)",
                        "a fault in the release itself: delete the GitHub Release if one was made, fix on " + v.branch()
                                + ", promote again, then move the tag: git tag -f " + v.tag() + " <commit> && git push -f origin " + v.tag()));
        ok("the Release workflow passed");
        String testedJob = settings.get("tested.job");
        if (gh.jobNames(run).stream().anyMatch(n -> n.endsWith(testedJob))) {
            ok("it reused the Build on main (job \"" + testedJob + "\")");
        } else {
            System.out.println("note: it ran every test itself (no job \"" + testedJob + "\"); the Build on main had not passed when the tag came");
        }
        verifyRelease(v);
    }

    /** The release as a user meets it: the GitHub Release's files, their signatures, the update site. */
    private void verifyRelease(Version v) {
        List<String> assets = gh.releaseAssets(v.tag());
        String zip = zipName(v);
        if (!assets.contains(zip) || !assets.contains(zip + ".asc")) {
            throw new ToolFailure("the GitHub Release " + v.tag() + " lacks " + zip + " or " + zip + ".asc (it has " + assets + ")");
        }
        ok("the GitHub Release has " + zip + " and " + zip + ".asc");

        Path folder = root.resolve("target/release-tool/" + v.tag());
        Folders.deleteTree(folder);
        shell.out(List.of("gh", "release", "download", v.tag(), "--pattern", zip, "--pattern", zip + ".asc", "--dir", folder.toString()));
        String key = settings.get("signing.key");
        Optional<Path> bash = shell.bash();
        if (bash.isPresent() && Shell.onPath("gpg")) {
            List<String> verifyZip = List.of(bash.get().toString(), "releng/verify-zip.sh", folder.resolve(zip).toString(), key);
            if (shell.show(verifyZip) != 0) {
                throw new ToolFailure("the ZIP's signature does not verify (see above); do not announce the release");
            }
            Path site = folder.resolve("site");
            Folders.unzip(folder.resolve(zip), site);
            if (Shell.onPath("python3")) {
                if (shell.show(List.of(bash.get().toString(), "releng/verify-signatures.sh", site.toString(), key)) != 0) {
                    throw new ToolFailure("the jar signatures in the ZIP do not verify (see above); do not announce the release");
                }
            } else {
                System.out.println("note: no python3, so the jar signatures were not checked (releng/verify-signatures.sh)");
            }
        } else {
            System.out.println("note: no bash (on Windows: Git for Windows' bash) or gpg here, so the signatures were not checked (releng/verify-zip.sh)");
        }

        awaitSite(v);
        System.out.println();
        System.out.println("Release " + v + " is out.");
        offerInstallCheck(v);
    }

    /**
     * Offers {@code InstallCheck}: the release installed from the live update site into a copy of
     * your Eclipse, as a user installs it. Recommended, never required: {@code next} does not wait
     * for it (a fix for a release that fails it ships from the branch {@code next} cuts), and it
     * can fail for reasons of this machine's Eclipse. The status shows whether it passed.
     *
     * <p>Only a person's yes runs it: {@code --yes} approves the tag step's own side effects, not a
     * new copy of an Eclipse and three Eclipse starts besides, so with {@code --yes} the offer is
     * asked like any other (and, with no terminal, only recommended).
     */
    private void offerInstallCheck(Version v) {
        List<String> command = Shell.java(INSTALL_CHECK, v.toString());
        Ask asking = options.mode() == Ask.Mode.YES ? new Ask(Ask.Mode.ASK, options.rerunWithYes("tag")) : ask;
        if (asking.offer("Install " + v + " from the update site into a copy of your Eclipse (InstallCheck)", "java " + INSTALL_CHECK + " " + v)
                && shell.show(command) != 0) {
            System.out.println("note: InstallCheck failed (see above); the release itself is out. Run it again with: java "
                    + INSTALL_CHECK + " " + v);
        }
    }

    /** How often and how long {@link #awaitSite} asks the update site before it gives up. */
    private static final int SITE_LOOK_SECONDS = 30;
    private static final int SITE_LOOK_TIMES = 20;

    /**
     * Waits until the public update site lists {@code v} and serves its {@code content.jar}. Each
     * look goes past GitHub Pages' cache ({@link UpdateSite#get}), and the look is repeated for a
     * while, as a deployment takes a moment to reach every server.
     */
    private void awaitSite(Version v) {
        String site = settings.get("site.url");
        for (int i = 0; i < SITE_LOOK_TIMES; i++) {
            String composite = UpdateSite.get(site + "compositeContent.xml");
            boolean listed = composite != null && UpdateSite.releases(composite).contains(v);
            if (listed && UpdateSite.get(site + "releases/" + v + "/content.jar") != null) {
                ok("the update site " + site + " lists and serves " + v);
                return;
            }
            if (i == 0) {
                System.out.println("waiting for the update site to list and serve " + v + " …");
            }
            sleepSeconds(SITE_LOOK_SECONDS);
        }
        throw new ToolFailure("the update site " + site + " does not list or serve releases/" + v + " after "
                + (SITE_LOOK_SECONDS * SITE_LOOK_TIMES / 60) + " minutes; look at the Release run's Update site jobs");
    }

    private static void sleepSeconds(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("interrupted");
        }
    }

    // ---------------------------------------------------------------- next

    private void next() {
        Version v = pomVersion();
        git.fetch();
        if (remoteTagCommit(v.tag()) == null) {
            throw new ToolFailure(v.tag() + " is not on origin: release " + v + " first (tag)");
        }
        Version next = nextVersion(v);
        String branch = next.branch();
        String main = git.commitOf("origin/main");
        if (main == null) {
            throw new ToolFailure("origin has no main branch to cut " + branch + " from, or to tell finished branches by");
        }
        if (git.commitOf("origin/" + branch) != null) {
            ok(branch + " is on origin already");
        } else if (options.mode() == Ask.Mode.DRY_RUN) {
            System.out.println("[dry run] would cut " + branch + " from origin/main, set the version to " + next.snapshot() + ", commit and push it");
        } else {
            requireClean();
            boolean exists = git.commitOf("refs/heads/" + branch) != null;
            if (!(exists && branchVersion(branch).equals(next.snapshot()))) {
                cutNextBranch(next, branch, main, exists);
            } else if (!git.branch().equals(branch)) {
                git.out("switch", "-q", branch);
            }
            git.push(ask, "Push " + branch + " to origin", "--set-upstream", branch);
        }
        deleteFinishedBranches(v, main);
        System.out.println();
        System.out.println(branch + " is the branch for " + next + ".");
    }

    /**
     * Cuts {@code branch} from {@code main} (or takes the local one left from an earlier try), sets
     * its version and commits it, after one question for all three: they are local, but a stop in
     * the middle would leave a branch and eleven edited files behind, and the printed rerun would
     * then refuse the unclean tree. A failure after the question puts everything back.
     */
    private void cutNextBranch(Version next, String branch, String main, boolean exists) {
        String message = "Start " + next + ": set the version to " + next.snapshot();
        String trailer = settings.get("commit.trailer", "");
        List<String> setVersion = Shell.maven("-q", "-B", "-ntp", "org.eclipse.tycho:tycho-versions-plugin:set-version",
                "-DnewVersion=" + next.snapshot());
        List<String> plan = List.of(exists ? "git switch " + branch : "git switch -c " + branch + " " + short7(main),
                Shell.display(setVersion));
        String before = git.branch();
        try {
            git.editAndCommit(ask, "Cut " + branch + " from origin/main, set its version to " + next.snapshot() + " and commit", plan,
                    trailer.isEmpty() ? message : message + "\n\n" + trailer, () -> {
                        git.out(exists ? new String[] {"switch", "-q", branch} : new String[] {"switch", "-q", "-c", branch, main});
                        if (shell.show(setVersion) != 0 || !pomVersionText().equals(next.snapshot())) {
                            throw new ToolFailure("setting the version to " + next.snapshot() + " failed (see above)");
                        }
                        return git.out("diff", "--name-only").lines().toList();
                    });
        } catch (ToolFailure f) {
            // editAndCommit put the files back; the branch switch is this step's own to undo. A stop
            // at the question switched nothing, and its message stands as it is.
            if (before.isEmpty() || git.branch().equals(before)) {
                throw f;
            }
            git.ok("switch", "-q", before);
            if (!exists) {
                git.ok("branch", "-q", "-D", branch);
            }
            throw new ToolFailure(f.getMessage() + " Back on " + before + (exists ? "." : ", and " + branch + " is deleted again."));
        }
    }

    /** The POM version on the tip of local {@code branch}. */
    private String branchVersion(String branch) {
        String pom = git.out("show", branch + ":pom.xml"); // a failing git stops the step, as before
        try {
            return Version.inPom(pom);
        } catch (ToolFailure e) {
            return ""; // a POM without the project's version: not the branch next sets up
        }
    }

    /** Deletes, each after asking, the old release branch and the branches {@code main} already has. */
    private void deleteFinishedBranches(Version v, String main) {
        List<String> remote = new ArrayList<>();
        if (git.commitOf("origin/" + v.branch()) != null && git.isAncestor("origin/" + v.branch(), main)) {
            remote.add(v.branch());
        }
        String rehearsePrefix = settings.get("rehearse.prefix");
        for (String ref : git.out("branch", "-r", "--merged", main, "--format=%(refname:short)").lines().toList()) {
            String name = ref.startsWith("origin/") ? ref.substring("origin/".length()) : null;
            if (name != null && (Git.isTopicBranch(name) || name.startsWith(rehearsePrefix))) {
                remote.add(name);
            }
        }
        for (String name : remote) {
            git.push(ask, "Delete " + name + " on origin (main has all of it)", "--delete", name);
        }
        String current = git.branch();
        for (String name : git.out("branch", "--merged", main, "--format=%(refname:short)").lines().toList()) {
            boolean finished = name.equals(v.branch()) || Git.isTopicBranch(name);
            if (finished && !name.equals(current)) {
                List<String> delete = List.of("git", "branch", "-d", name);
                if (ask.confirm("Delete the local branch " + name + " (main has all of it)", List.of(Shell.display(delete)))) {
                    shell.out(delete);
                }
            }
        }
    }

    // ---------------------------------------------------------------- shared

    /**
     * Follows the run that a push or dispatch started; on failure, prints the failed jobs' last log
     * lines and {@code recovery}, then stops the tool.
     */
    private GitHub.Run awaitAndWatch(String what, String workflow, String commit, String event, String branch, Set<Long> older,
            List<String> recovery) {
        GitHub.Run run = gh.awaitRun(workflow, commit, event, branch, older);
        if (gh.watch(run)) {
            return run;
        }
        System.err.println();
        System.err.println(what + " failed: " + run.url());
        System.err.println("---- what failed, from the failed jobs' logs (" + run.url() + " has all of them)");
        System.err.println(gh.failedLogSummary(run, LOG_LINES));
        System.err.println("----");
        System.err.println("To recover:");
        System.err.println("  gh run rerun " + run.id() + " --failed      (" + recovery.get(0) + ")");
        for (String line : recovery.subList(1, recovery.size())) {
            System.err.println("  " + line);
        }
        throw new ToolFailure(what + " failed; nothing after it was done");
    }

    private Version pomVersion() {
        return Version.ofSnapshot(pomVersionText());
    }

    private String pomVersionText() {
        return Version.inPom(read("pom.xml"));
    }

    private Version requireReleaseBranch() {
        Version v = pomVersion();
        String branch = git.branch();
        if (!branch.equals(v.branch())) {
            throw new ToolFailure("this is " + (branch.isEmpty() ? "a detached HEAD" : branch) + "; release " + v + " is made on " + v.branch()
                    + ": git switch " + v.branch());
        }
        return v;
    }

    private void requireClean() {
        if (!git.isClean()) {
            throw new ToolFailure("the working tree has changes (git status); commit or stash them first");
        }
    }

    private Version nextVersion(Version v) {
        Version next = options.next() != null ? options.next() : v.nextPatch();
        if (next.compareTo(v) <= 0) {
            throw new ToolFailure("--next " + next + " is not after " + v);
        }
        return next;
    }

    /**
     * The commit origin's {@code tag} names, or {@code null} when origin has no such tag: asked of
     * origin itself, not of the local tag, which may be a leftover. For an annotated tag, git lists
     * the tag object and, as {@code refs/tags/v0.2.3^{}}, the commit; the commit is the one wanted.
     */
    private String remoteTagCommit(String tag) {
        String ref = "refs/tags/" + tag;
        String commit = null;
        for (String line : shell.out(List.of("git", "ls-remote", "--tags", "origin", ref, ref + "^{}")).lines().toList()) {
            String[] f = line.split("\\s+");
            if (f.length == 2 && (f[1].equals(ref + "^{}") || f[1].equals(ref) && commit == null)) {
                commit = f[0];
            }
        }
        return commit;
    }

    private boolean testsCover(String head) {
        String tested = testedCommit();
        if (tested.isEmpty() || git.commitOf(tested) == null) {
            return false;
        }
        boolean ancestor = git.isAncestor(tested, head);
        return ReleaseFiles.testsCover(tested, head, ancestor, ancestor ? git.changedFiles(tested, head) : List.of());
    }

    private Path testedFile() {
        return root.resolve("target/release-tool/tested");
    }

    /** The commit {@code check} recorded, or the empty string when there is none or the file holds anything but a commit id. */
    private String testedCommit() {
        try {
            String text = Files.isRegularFile(testedFile()) ? Files.readString(testedFile()).strip() : "";
            return text.matches("[0-9a-f]{40}") ? text : "";
        } catch (IOException e) {
            return "";
        }
    }

    private void writeTested(String commit) {
        try {
            Files.createDirectories(testedFile().getParent());
            Files.writeString(testedFile(), commit + "\n");
        } catch (IOException e) {
            throw new ToolFailure("cannot write " + testedFile() + ": " + e.getMessage());
        }
    }

    private String aheadBehind(String upstream, String head) {
        String[] counts = git.out("rev-list", "--left-right", "--count", upstream + "..." + head).split("\\s+");
        int behind = Integer.parseInt(counts[0]);
        int ahead = Integer.parseInt(counts[1]);
        return behind == 0 && ahead == 0 ? "in step with origin" : ahead + " ahead of origin, " + behind + " behind";
    }

    /** A commit id as git shortens it for people ({@code 9c7d0d2}); a shorter text as it is. */
    static String short7(String commit) {
        return commit.length() > 7 ? commit.substring(0, 7) : commit;
    }

    private static String zipName(Version v) {
        return "neovim4eclipse-" + v + ".zip";
    }

    private String gitShow(String commit, String file) {
        return git.out("show", commit + ":" + file);
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

    private static void row(String name, String value) {
        System.out.printf("  %-15s %s%n", name, value);
    }
}
