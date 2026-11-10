// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The git questions and commands the tools need, on the repository at {@link Shell#root}. The
 * questions ({@link #head}, {@link #isClean}, …) only read. The commands that change something
 * shared ({@link #push}, …) take the {@link Ask} gate, so none runs unasked; local work (a fetch, a
 * new local branch) runs freely.
 */
public final class Git {

    private final Shell shell;

    public Git(Shell shell) {
        this.shell = shell;
    }

    /** {@code git <args>}, which must succeed; its output. */
    public String out(String... args) {
        return shell.out(command(args));
    }

    /** Whether {@code git <args>} exits with status 0 (for yes/no questions such as {@code merge-base --is-ancestor}). */
    public boolean ok(String... args) {
        return shell.capture(command(args)).ok();
    }

    public String head() {
        return out("rev-parse", "HEAD");
    }

    /** The commit {@code ref} names, or {@code null} if there is none (a remote branch not fetched, a tag not made). */
    public String commitOf(String ref) {
        Shell.Result r = shell.capture(command("rev-parse", "--verify", "--quiet", ref + "^{commit}"));
        return r.ok() ? r.out() : null;
    }

    /** The current branch, or the empty string when HEAD is detached. */
    public String branch() {
        return out("branch", "--show-current");
    }

    /**
     * The branches an upgrade tool may commit on (CONTRIBUTING.md, "Branches"): the branch of the
     * release being made, {@code release}'s, or a {@code fix/} or {@code feature/} branch cut from
     * it. Never {@code main}, which moves only at a release; never a detached HEAD, where a commit
     * belongs to no branch; and never an earlier release's branch, which is finished: a move
     * committed there would not reach the next release. Example, while 0.2.11 is made:
     * {@code release/0.2.11} and {@code feature/maven-tool} are, {@code main} and
     * {@code release/0.2.10} are not.
     */
    public static boolean isWorkBranch(String branch, Version release) {
        return branch.equals(release.branch()) || isTopicBranch(branch);
    }

    /**
     * Stops the tool unless the current branch is a work branch ({@link #isWorkBranch}) for the
     * release the root POM's version {@code pomVersion} names ({@link Version#inPom}); {@code what}
     * names the move in the message: {@code "move Maven"} on {@code main} gives "this is main; move
     * Maven on release/0.2.11, or on a fix/ or feature/ branch cut from it". A POM version without
     * {@code -SNAPSHOT} (on {@code main} after a release) names no release being made, so then only
     * a {@code fix/} or {@code feature/} branch would pass, and the message says "the release branch".
     * A version whose tag exists ({@code v0.2.10}) is finished: the tool stops on any branch.
     */
    public void checkWorkBranch(String what, String pomVersion) {
        String branch = branch();
        Version release = pomVersion.endsWith("-SNAPSHOT") ? Version.ofSnapshot(pomVersion) : null;
        // A release keeps its -SNAPSHOT version in the POM (the tag marks it), so a finished
        // release's branch, or a fix/ branch cut from it, still names that release: its tag tells
        // (once fetched: a clone that has not fetched since the release does not know it yet).
        if (release != null && commitOf("refs/tags/" + release.tag()) != null) {
            throw new ToolFailure("this checkout is of " + release + ", which is released (tag " + release.tag() + "); " + what
                    + " on the next release's branch, release/<next version>, or a fix/ or feature/ branch cut from it"
                    + " (CONTRIBUTING.md, \"Branches\")");
        }
        if (release == null ? !isTopicBranch(branch) : !isWorkBranch(branch, release)) {
            throw new ToolFailure("this is " + (branch.isEmpty() ? "a detached HEAD" : branch) + "; " + what + " on "
                    + (release == null ? "the release branch" : release.branch())
                    + ", or on a fix/ or feature/ branch cut from it (CONTRIBUTING.md, \"Branches\")");
        }
    }

    /**
     * A {@code fix/} or {@code feature/} branch: one piece of work, merged into the release branch
     * and then deleted (the Release tool's {@code next} step deletes those {@code main} has).
     */
    public static boolean isTopicBranch(String branch) {
        return branch.startsWith("fix/") || branch.startsWith("feature/");
    }

    /** No changes, staged or not, and no untracked files (ignored ones do not count). */
    public boolean isClean() {
        return out("status", "--porcelain").isEmpty();
    }

    /** Whether {@code ancestor} is {@code commit} or comes before it, so that a push of {@code commit} over it is a fast-forward. */
    public boolean isAncestor(String ancestor, String commit) {
        return ok("merge-base", "--is-ancestor", ancestor, commit);
    }

    /**
     * Fetches origin's branches, dropping those deleted there, and its tags; changes nothing shared.
     * Two fetches, because {@code git fetch --prune --tags} would also delete every local tag that
     * origin does not have (with {@code --tags}, tags are pruned like branches), and a tag made here
     * on purpose is not the tool's to remove. A tag deleted on origin therefore stays here; the tools
     * ask origin itself ({@code git ls-remote}) whether a tag exists.
     */
    public void fetch() {
        out("fetch", "--prune", "origin");
        out("fetch", "--tags", "origin");
    }

    /** The files that differ between two commits. */
    public List<String> changedFiles(String from, String to) {
        String names = out("diff", "--name-only", from, to);
        return names.isEmpty() ? List.of() : names.lines().toList();
    }

    /**
     * The one way a step edits tracked files and commits them, so that a stop never leaves half an
     * edit behind: on a clean tree, it asks once, before the first edit, showing {@code plan};
     * then {@code edit} runs (it changes the files and returns those to commit) and the commit is
     * made, in UTC. If {@code edit} or the commit fails, the tree is put back as it was
     * ({@code git reset --hard}: it was clean, so only the step's own edits go) before the failure
     * is reported. With no terminal to ask on, the question stops the step before any edit, so the
     * printed {@code --yes} command finds the tree as clean as the first try did. In a dry run
     * nothing is edited; the answer is {@code false} then, {@code true} after a commit.
     *
     * <p>Example, the Release tool's {@code prepare}:
     * <pre>
     *   git.editAndCommit(ask, "Date the changelog and name 0.2.3 in the README, then commit",
     *           List.of("CHANGELOG.md: ## [0.2.3] - 2026-11-20 under a new ## [Unreleased]", …),
     *           "Release 0.2.3: date its changelog section, name it in the README", () -&gt; {
     *               write(…); return List.of("CHANGELOG.md", "README.md");
     *           });
     * </pre>
     */
    public boolean editAndCommit(Ask ask, String what, List<String> plan, String message, Supplier<List<String>> edit) {
        if (!isClean()) {
            throw new ToolFailure("the working tree has changes (git status); commit or stash them first");
        }
        List<String> details = new ArrayList<>(plan);
        details.add("git commit -m \"" + message.lines().findFirst().orElse("") + "\"  (in UTC)");
        if (!ask.confirm(what, details)) {
            return false;
        }
        try {
            commitNow(message, edit.get());
            return true;
        } catch (ToolFailure f) {
            ok("reset", "-q", "--hard", "HEAD");
            throw new ToolFailure(f.getMessage() + " The working tree is as it was before the step.");
        }
    }

    /**
     * Commits {@code files} with {@code message}, without asking (the question came first, in
     * {@link #editAndCommit}). In UTC ({@code TZ=UTC}), so that the date does not tell the
     * machine's time zone (AGENTS.md, "Conventions"); a {@code Co-Authored-By:} line or any other
     * last paragraph is part of {@code message}.
     */
    private void commitNow(String message, List<String> files) {
        List<String> add = new ArrayList<>(List.of("add", "--"));
        add.addAll(files);
        out(add.toArray(String[]::new));
        Shell.Result r = shell.capture(command("commit", "-q", "-m", message), Map.of("TZ", "UTC"));
        if (!r.ok()) {
            // Unstaged again, so that a caller who puts the files back leaves a clean tree (a commit
            // hook refused, or git has no user.email yet).
            List<String> reset = new ArrayList<>(List.of("reset", "-q", "--"));
            reset.addAll(files);
            ok(reset.toArray(String[]::new));
            throw new ToolFailure("git commit failed: " + (r.err().isEmpty() ? r.out() : r.err()));
        }
        System.out.println("committed " + out("log", "-1", "--format=%h %s"));
    }

    /**
     * {@code git push origin <refspecs>}, after the gate. Never forced: a push git refuses (main
     * moved on, a tag already there) fails here, and the person decides what to do.
     */
    public void push(Ask ask, String what, String... refspecs) {
        List<String> args = new ArrayList<>(List.of("push", "origin"));
        args.addAll(List.of(refspecs));
        List<String> command = command(args.toArray(String[]::new));
        if (ask.confirm(what, List.of(Shell.display(command)))) {
            if (shell.show(command) != 0) {
                throw new ToolFailure(Shell.display(command) + " failed; nothing was forced. See git's message above.");
            }
        }
    }

    private static List<String> command(String... args) {
        List<String> c = new ArrayList<>(List.of("git"));
        c.addAll(List.of(args));
        return c;
    }
}
