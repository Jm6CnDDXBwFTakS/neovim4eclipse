// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package common;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * GitHub Actions and Releases through the {@code gh} command (signed in with {@code gh auth login}).
 * {@code gh}'s {@code --jq} turns its JSON into plain tab-separated lines here, so the tools need no
 * JSON library: for example {@link #runs} asks for
 * <pre>
 *   gh run list --workflow build.yml --commit 9c7d0d2… --json databaseId,status,conclusion,url,event,headBranch
 *               --jq '.[] | "\(.databaseId)\t\(.status)\t…"'
 * </pre>
 * and reads {@code 42186112399	completed	success	https://github.com/…/runs/42186112399	push	main}.
 */
public final class GitHub {

    /** One workflow run. {@code conclusion} is empty while it runs; {@code success} when it passed. */
    public record Run(long id, String status, String conclusion, String url, String event, String branch) {
        public boolean completed() {
            return status.equals("completed");
        }

        public boolean passed() {
            return completed() && conclusion.equals("success");
        }
    }

    /** How often and how long {@link #awaitRun} looks for a run GitHub has not listed yet. */
    private static final int LOOK_SECONDS = 5;
    private static final int LOOK_TIMES = 36;

    private final Shell shell;

    public GitHub(Shell shell) {
        this.shell = shell;
    }

    /** Fails with what to do when {@code gh} is missing or not signed in. */
    public void requireReady() {
        if (!Shell.onPath("gh")) {
            throw new ToolFailure("gh is not installed; it is GitHub's command-line tool: https://cli.github.com");
        }
        Shell.Result r = shell.capture(List.of("gh", "auth", "status"));
        if (!r.ok()) {
            throw new ToolFailure("gh is not signed in to GitHub: run gh auth login");
        }
    }

    /** The runs of {@code workflow} for {@code commit} from {@code event} on {@code branch} (a tag's name for a tag push), newest first. */
    public List<Run> runs(String workflow, String commit, String event, String branch) {
        String out = shell.out(List.of("gh", "run", "list", "--workflow", workflow, "--commit", commit, "--event", event,
                "--branch", branch, "--limit", "20", "--json", "databaseId,status,conclusion,url,event,headBranch",
                "--jq", ".[] | \"\\(.databaseId)\\t\\(.status)\\t\\(.conclusion)\\t\\(.url)\\t\\(.event)\\t\\(.headBranch)\""));
        List<Run> runs = new ArrayList<>();
        for (String line : out.lines().toList()) {
            parseRun(line).ifPresent(runs::add);
        }
        return runs;
    }

    /** One line of {@link #runs}' output as a run; empty for a line that is not one. */
    public static Optional<Run> parseRun(String line) {
        String[] f = line.split("\t", -1);
        if (f.length != 6 || !f[0].matches("\\d+")) {
            return Optional.empty();
        }
        return Optional.of(new Run(Long.parseLong(f[0]), f[1], f[2], f[3], f[4], f[5]));
    }

    /** The ids of the runs {@link #runs} lists now: taken before a push, so that {@link #awaitRun} can tell the new run from older ones. */
    public Set<Long> runIds(String workflow, String commit, String event, String branch) {
        Set<Long> ids = new HashSet<>();
        for (Run run : runs(workflow, commit, event, branch)) {
            ids.add(run.id());
        }
        return ids;
    }

    /**
     * The newest of {@code runs} (newest first, as gh lists them) that is not one of {@code older}.
     * Example: a rehearsal of commit abc failed as run 41; the same commit pushed again starts run
     * 42. With {@code older = {41}} this is 42, or empty until GitHub lists it, never the failed 41.
     */
    public static Optional<Run> newestExcept(List<Run> runs, Set<Long> older) {
        return runs.stream().filter(r -> !older.contains(r.id())).findFirst();
    }

    /**
     * The newest run as in {@link #runs} that is not one of {@code older}, waiting up to three
     * minutes for GitHub to list it: a run appears a few seconds after the push that starts it,
     * sometimes later. {@code older} holds the runs that existed before this tool's push or dispatch
     * ({@link #runIds}); empty when the tool started nothing and follows the run that is there.
     */
    public Run awaitRun(String workflow, String commit, String event, String branch, Set<Long> older) {
        for (int i = 0; i < LOOK_TIMES; i++) {
            Optional<Run> run = newestExcept(runs(workflow, commit, event, branch), older);
            if (run.isPresent()) {
                return run.get();
            }
            if (i == 0) {
                System.out.println("waiting for GitHub to list the " + workflow + " run for " + branch + " …");
            }
            sleep(LOOK_SECONDS);
        }
        throw new ToolFailure("no " + workflow + " run for " + branch + " at " + commit.substring(0, 7) + " after "
                + (LOOK_SECONDS * LOOK_TIMES / 60) + " minutes; look at the repository's Actions page");
    }

    /** How many times {@link #watch} starts {@code gh run watch} again when it ends before the run does. */
    private static final int WATCH_TRIES = 5;

    /**
     * Follows {@code run} on this terminal until it ends ({@code gh run watch}); whether it passed.
     * A run that ended already is only looked up. {@code gh run watch} can end before the run does
     * (a dropped connection during an hour-long Build, an expired sign-in); its exit status says
     * nothing about the run then, so the run's own status decides: still running means watch again,
     * a few times, and then stop with a {@link ToolFailure} that says so, never "failed".
     */
    public boolean watch(Run run) {
        System.out.println("watching " + run.url());
        for (int i = 0; i <= WATCH_TRIES; i++) {
            String[] state = shell.out(List.of("gh", "run", "view", Long.toString(run.id()), "--json", "status,conclusion",
                    "--jq", ".status + \"\\t\" + .conclusion")).split("\t", -1);
            if (state[0].equals("completed")) {
                return state.length > 1 && state[1].equals("success");
            }
            if (i == WATCH_TRIES) {
                break;
            }
            if (i > 0) {
                System.out.println("gh run watch ended while the run is " + state[0] + "; watching again");
                sleep(LOOK_SECONDS);
            }
            shell.show(List.of("gh", "run", "watch", Long.toString(run.id()), "--compact", "--interval", "30"));
        }
        throw new ToolFailure("could not follow " + run.url() + " to its end (gh run watch kept stopping); it may still be"
                + " running: run the same step again to follow it");
    }

    /** The last {@code lines} lines of the failed jobs' logs, for a report after a failed run. */
    public String failedLogTail(Run run, int lines) {
        Shell.Result r = shell.capture(List.of("gh", "run", "view", Long.toString(run.id()), "--log-failed"));
        List<String> all = r.out().lines().toList();
        return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
    }

    /** The names of a run's jobs, as on its page (a called workflow's job as {@code build / Build (tested on main)}). */
    public List<String> jobNames(Run run) {
        String out = shell.out(List.of("gh", "run", "view", Long.toString(run.id()), "--json", "jobs", "--jq", ".jobs[].name"));
        return out.isEmpty() ? List.of() : out.lines().toList();
    }

    /** The file names attached to the GitHub Release of {@code tag}; empty when there is no such release. */
    public List<String> releaseAssets(String tag) {
        Shell.Result r = shell.capture(List.of("gh", "release", "view", tag, "--json", "assets", "--jq", ".assets[].name"));
        return r.ok() && !r.out().isEmpty() ? r.out().lines().toList() : List.of();
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("interrupted");
        }
    }
}
