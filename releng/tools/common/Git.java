// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /** No changes, staged or not, and no untracked files (ignored ones do not count). */
    public boolean isClean() {
        return out("status", "--porcelain").isEmpty();
    }

    /** Whether {@code ancestor} is {@code commit} or comes before it, so that a push of {@code commit} over it is a fast-forward. */
    public boolean isAncestor(String ancestor, String commit) {
        return ok("merge-base", "--is-ancestor", ancestor, commit);
    }

    /** Fetches origin's branches and tags, dropping deleted ones; reads only, changes nothing shared. */
    public void fetch() {
        out("fetch", "--prune", "--tags", "origin");
    }

    /** The files that differ between two commits. */
    public List<String> changedFiles(String from, String to) {
        String names = out("diff", "--name-only", from, to);
        return names.isEmpty() ? List.of() : names.lines().toList();
    }

    /**
     * Commits what is staged, after the gate, in UTC ({@code TZ=UTC}) so that the date does not tell
     * the machine's time zone (AGENTS.md, "Conventions"). {@code trailer}, if not empty, is added as
     * the message's last paragraph, for example a {@code Co-Authored-By:} line.
     */
    public void commit(Ask ask, String message, String trailer, List<String> files) {
        String full = trailer.isEmpty() ? message : message + "\n\n" + trailer;
        List<String> details = new ArrayList<>();
        details.add("git commit: " + message);
        details.addAll(shell.out(command("diff", "--stat", "HEAD", "--")).lines().toList());
        if (ask.confirm("Commit " + String.join(", ", files), details)) {
            List<String> add = new ArrayList<>(List.of("add", "--"));
            add.addAll(files);
            out(add.toArray(String[]::new));
            Shell.Result r = shell.capture(command("commit", "-q", "-m", full), Map.of("TZ", "UTC"));
            if (!r.ok()) {
                throw new ToolFailure("git commit failed: " + r.err());
            }
            System.out.println("committed " + out("log", "-1", "--format=%h %s"));
        }
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
