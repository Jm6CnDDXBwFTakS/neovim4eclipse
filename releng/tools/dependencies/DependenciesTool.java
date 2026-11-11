// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package dependencies;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import common.Ask;
import common.Changelog;
import common.Git;
import common.GitHub;
import common.Settings;
import common.Shell;
import common.ToolFailure;
import common.VersionCopies;
import common.Version;

/**
 * The Dependency tool: moves the build's libraries, Maven plug-ins and GitHub Actions to their
 * newest releases, the updates Dependabot would propose (docs/UPGRADING.md, "Libraries, plug-ins
 * and actions").
 * <pre>
 *   java releng/tools/dependencies/Dependencies.java                  every update the rules allow
 *   java releng/tools/dependencies/Dependencies.java --major junit    also JUnit's next major release
 *   flags: --allow-young (releases under a week old), --no-tests, --yes, --dry-run (as the Release tool)
 * </pre>
 *
 * <h2>Why a tool, and not Dependabot's pull requests</h2>
 * Dependabot's pull requests target {@code main}, which moves only at a release, while the work
 * goes on the release branch. The tool looks where Dependabot looks (Maven Central, the actions'
 * GitHub releases), follows {@code .github/dependabot.yml}'s rules, and commits on the release
 * branch, where the tests run before the release.
 *
 * <h2>The steps</h2>
 * <ol>
 *   <li>Find the updates ({@link Updates}): each version property of the root {@code pom.xml} and
 *       each action of the workflows, to its newest release that is a week old and that the rules
 *       allow. Majors and young releases are only named, in notes.</li>
 *   <li>One commit per Dependabot group ({@code build-tools}: Tycho and the Maven plug-ins;
 *       {@code actions}: every action), and one per dependency in no group (msgpack-core, JUnit,
 *       JaCoCo), each after y/N. A {@code --major} move is a commit of its own.</li>
 *   <li>Before an embedded library's commit, its license files and dependencies are checked
 *       ({@link Embedded}), and its commit gets a changelog entry: it ships inside the plug-in.
 *       Nothing else does, so the other commits have none.</li>
 *   <li>Every test once, after the last commit ({@code LikeCi --all}). Actions run only on
 *       GitHub, so with nothing but actions moved no test runs here: the next CI run is theirs.</li>
 * </ol>
 */
public final class DependenciesTool {

    static final String TOOL = "releng/tools/dependencies/Dependencies.java";
    private static final String USAGE = "Usage: java " + TOOL + " [--major <name>]... [--allow-young] [--no-tests] [--yes] [--dry-run]";

    private final Path root;
    private final Shell shell;
    private final Git git;
    private final Settings settings;
    private final Ask ask;
    private final Set<String> majors;
    private final boolean allowYoung;
    private final boolean tests;

    private DependenciesTool(Path root, Ask ask, Set<String> majors, boolean allowYoung, boolean tests) {
        this.root = root;
        this.shell = new Shell(root);
        this.git = new Git(shell);
        this.settings = Settings.load(List.of(root.resolve(Settings.COMMON), root.resolve("releng/tools/dependencies")), Map.of());
        this.ask = ask;
        this.majors = majors;
        this.allowYoung = allowYoung;
        this.tests = tests;
    }

    public static void main(String[] args) {
        try {
            Path root = Path.of("").toAbsolutePath();
            if (!Files.isRegularFile(root.resolve(TOOL))) {
                throw new ToolFailure("run this from the repository's root folder: java " + TOOL);
            }
            Set<String> majors = new LinkedHashSet<>();
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
                    case "--major" -> {
                        if (i + 1 >= args.length || !args[i + 1].matches("[\\w.-]+(/[\\w.-]+)?")) {
                            throw new ToolFailure("--major needs a name such as junit or actions/checkout\n" + USAGE);
                        }
                        majors.add(args[++i]);
                        rerun.add(a);
                        a = args[i];
                    }
                    default -> throw new ToolFailure("unknown argument " + a + "\n" + USAGE);
                }
                if (!a.equals("--yes") && !a.equals("--dry-run")) {
                    rerun.add(a);
                }
            }
            rerun.add("--yes");
            new DependenciesTool(root, new Ask(mode, String.join(" ", rerun)), majors, allowYoung, tests).run();
        } catch (ToolFailure f) {
            System.err.println();
            System.err.println("Dependencies: " + f.getMessage());
            System.exit(1);
        }
    }

    /** One commit: a group's moves, or one dependency's. */
    record Commit(String name, List<Updates.MavenMove> maven, List<Updates.ActionMove> actions) {
        int size() {
            return maven.size() + actions.size();
        }
    }

    private void run() {
        git.checkWorkBranch("move dependencies", Version.inPom(read("pom.xml")));
        if (!git.isClean()) {
            throw new ToolFailure("the working tree has changes (git status); commit or stash them first");
        }
        new GitHub(shell).requireReady(); // the actions' releases are read through gh
        Updates.Found found = new Updates(root, shell, settings, allowYoung, majors).find();
        for (String ok : found.upToDate()) {
            System.out.println("ok: " + ok + " is the newest the rules allow");
        }
        for (String note : found.notes()) {
            System.out.println("  note: " + note);
        }
        if (found.count() == 0) {
            System.out.println("Nothing to move.");
            return;
        }
        System.out.println();
        System.out.println("To move: " + found.summary());

        List<Commit> commits = commits(found, majors);
        Path work = root.resolve("target/dependency-update");
        List<String> made = new ArrayList<>();
        boolean testable = false;
        for (Commit c : commits) {
            try {
                if (make(c, work)) {
                    made.add(git.out("log", "-1", "--format=%h %s"));
                }
            } catch (ToolFailure f) {
                if (made.isEmpty()) {
                    throw f;
                }
                // The commits made so far stay; their tests were to run after the last one.
                throw new ToolFailure(f.getMessage() + "\nThese commits are made, but no test ran with them:\n  " + String.join("\n  ", made)
                        + "\nRun java releng/tools/ci/LikeCi.java --all, or take one back with git revert <commit>.");
            }
            testable |= !c.maven().isEmpty();
        }
        System.out.println();
        if (!testable) {
            System.out.println("No test runs here: only actions moved, and they run on GitHub; the next CI run tests them.");
        } else if (!tests) {
            System.out.println("No tests (--no-tests): run java releng/tools/ci/LikeCi.java --all before you push.");
        } else if (ask.mode() == Ask.Mode.DRY_RUN) {
            System.out.println("[dry run] would run: java releng/tools/ci/LikeCi.java --all");
        } else if (shell.show(Shell.java("releng/tools/ci/LikeCi.java", "--all")) != 0) {
            throw new ToolFailure("the tests failed (see above). The commits are made:\n  " + String.join("\n  ", made)
                    + "\nFind the one at fault (git revert <commit> takes one back), fix it, and run the tests again.");
        } else {
            System.out.println("ok: every test passed with " + found.summary());
        }
        System.out.println();
        System.out.println("What stays yours: read the release notes of each move" + releaseNotes(found)
                + ", and on macOS and Windows run");
        System.out.println("  java releng/tools/ci/LikeCi.java --all");
        if (found.maven().stream().anyMatch(m -> m.property().equals("tycho.version"))) {
            System.out.println("Tycho moved: test signing with a throwaway key before the next release (the commands are at the top of"
                    + " releng/sign.sh; docs/UPGRADING.md, \"Build tools\").");
        }
    }

    /**
     * The moves as commits, in the order found: one per group, one per dependency without a group,
     * and one for each dependency named by {@code --major}, even inside a group, so that a major
     * can be taken back alone.
     */
    static List<Commit> commits(Updates.Found found, Set<String> majors) {
        Map<String, Commit> byName = new LinkedHashMap<>();
        for (Updates.MavenMove m : found.maven()) {
            String name = majors.contains(m.label()) ? m.label() : m.group().orElse(m.label());
            byName.computeIfAbsent(name, n -> new Commit(n, new ArrayList<>(), new ArrayList<>())).maven().add(m);
        }
        for (Updates.ActionMove a : found.actions()) {
            String name = majors.contains(a.repository()) ? a.repository() : a.group().orElse(a.repository());
            byName.computeIfAbsent(name, n -> new Commit(n, new ArrayList<>(), new ArrayList<>())).actions().add(a);
        }
        return new ArrayList<>(byName.values());
    }

    /** One commit's checks, plan, edits and commit, after y/N; false in a dry run. */
    private boolean make(Commit c, Path work) {
        List<String> plan = new ArrayList<>();
        Map<String, String> edits = new LinkedHashMap<>(); // file to its new text
        List<String> body = new ArrayList<>();
        List<String> embedded = Embedded.artifacts(settings);
        Optional<String> changelog = Optional.empty();
        for (Updates.MavenMove m : c.maven()) {
            edit(edits, "pom.xml", text -> PomVersions.withProperty(text, m.property(), m.from(), m.to()));
            plan.add("pom.xml: <" + m.property() + "> " + m.from() + " to " + m.to() + (m.major() ? " (a major release, --major)" : ""));
            for (VersionCopies.Copy copy : m.copies()) {
                edit(edits, copy.file(), text -> VersionCopies.rewrite(text, copy, m.to()));
                plan.add(copy.file() + ": the copy " + VersionCopies.FILE + " [" + m.property() + "] lists, " + m.from() + " to " + m.to());
            }
            body.add("- " + m.label() + " " + m.from() + " to " + m.to());
            for (PomVersions.Artifact a : m.artifacts()) {
                if (embedded.contains(a.toString())) {
                    System.out.println();
                    System.out.println("Checking " + a + " " + m.to() + ", which the plug-in embeds:");
                    Embedded.check(root, shell, settings, a, m.from(), m.to(), work);
                    String entry = "The embedded " + a.id() + " library is now " + m.to() + " (was " + m.from() + ").";
                    changelog = Optional.of(entry);
                    plan.add(Changelog.FILE + ": under Unreleased, Changed: " + entry);
                }
            }
        }
        for (Updates.ActionMove a : c.actions()) {
            String sha = shell.out(List.of("gh", "api", "repos/" + a.repository() + "/commits/" + a.toTag(), "--jq", ".sha")).strip();
            if (!WorkflowActions.isSha(sha)) {
                throw new ToolFailure("GitHub gave no commit SHA for " + a.repository() + " " + a.toTag() + ": " + sha);
            }
            for (String file : a.files()) {
                for (String from : a.fromShas()) {
                    edit(edits, file, text -> WorkflowActions.moved(file, text, a.repository(), from, sha, a.toTag()));
                }
            }
            plan.add(a.repository() + " " + a.from() + " to " + a.toTag() + " (" + sha + "), every line in " + String.join(", ", a.files())
                    + (a.major() ? "; a major release: read its migration notes first" : ""));
            body.add("- " + a.repository() + " " + a.from() + " to " + a.toTag());
        }
        String subject;
        if (c.size() == 1) {
            subject = c.maven().isEmpty() ? "Move to " + c.actions().get(0).repository() + " " + c.actions().get(0).toTag()
                    : "Move to " + c.maven().get(0).label() + " " + c.maven().get(0).to();
        } else {
            subject = "Move the " + c.name() + " group to newer releases";
        }
        // One move says it all in the subject; a group lists its moves.
        String message = subject + (c.size() == 1 ? "" : "\n\n" + String.join("\n", body)) + "\n\nFound by " + TOOL + ", which follows " + DependabotRules.FILE + ".";
        String trailer = settings.get("commit.trailer", "");
        if (!trailer.isEmpty()) {
            message += "\n\n" + trailer;
        }
        Optional<String> entry = changelog;
        return git.editAndCommit(ask, subject + ", then commit", plan, message, () -> {
            for (Map.Entry<String, String> e : edits.entrySet()) {
                write(e.getKey(), e.getValue());
            }
            entry.ifPresent(text -> write(Changelog.FILE, Changelog.withChangedEntry(read(Changelog.FILE), text)));
            List<String> expected = new ArrayList<>(edits.keySet());
            entry.ifPresent(text -> expected.add(Changelog.FILE));
            List<String> changed = git.out("diff", "--name-only", "HEAD").lines().toList();
            List<String> others = changed.stream().filter(f -> !expected.contains(f)).toList();
            if (!others.isEmpty()) {
                throw new ToolFailure("files other than the planned ones changed: " + others);
            }
            return changed;
        });
    }

    /** Applies {@code change} to the planned text of {@code file} (its text on disk the first time). */
    private void edit(Map<String, String> edits, String file, UnaryOperator<String> change) {
        edits.put(file, change.apply(edits.containsKey(file) ? edits.get(file) : read(file)));
    }

    private static String releaseNotes(Updates.Found found) {
        List<String> links = new ArrayList<>();
        for (Updates.ActionMove a : found.actions()) {
            links.add("https://github.com/" + a.repository() + "/releases/tag/" + a.toTag());
        }
        return links.isEmpty() ? "" : " (" + String.join(", ", links) + ")";
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
