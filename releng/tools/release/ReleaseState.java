// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package release;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import common.GitHub;
import common.Version;

/**
 * Where a release stands, read fresh from git and GitHub each time (there is no state file, so any
 * step can be run again, from any machine): which of the steps' outcomes are there already.
 * {@link #nextStep} turns that into the step to run next. For example, a release branch whose
 * changelog is dated and whose commit is on {@code main}, but with no tag yet, is at {@code tag}.
 *
 * @param version    the version being released (the POM's, without {@code -SNAPSHOT})
 * @param tested     the local tests passed on this commit, or on one that differs only in the release notes
 * @param prepared   the changelog has the version's dated section
 * @param onMain     {@code origin/main} is this commit
 * @param mainNotGreen the Build on {@code main} at this commit has runs, none of which passed (failed,
 *                   or still running, a rerun among them): {@code promote} follows it, and no tag is
 *                   made before it passes. No run at all is not this: the Release workflow then runs
 *                   every test itself.
 * @param tagged     origin has the version's tag
 * @param released   the GitHub Release has the ZIP and its signature
 * @param nextCut    origin has the next release branch
 */
public record ReleaseState(Version version, boolean tested, boolean prepared, boolean onMain, boolean mainNotGreen,
        boolean tagged, boolean released, boolean nextCut) {

    /** The step to run next, or {@code "done"}. */
    public String nextStep() {
        if (tagged) {
            // tag again until the Release has its files: it watches the run and verifies them.
            return !released ? "tag" : nextCut ? "done" : "next";
        }
        if (onMain) {
            // A Build on main that failed, or runs again, is followed to its end first (promote):
            // tagging now would release a commit whose tests did not pass.
            return mainNotGreen ? "promote" : "tag";
        }
        if (!tested) {
            return "check";
        }
        return prepared ? "promote" : "prepare";
    }

    /** {@code runs} newest first, of either kind: GitHub's run ids rise with creation; a rerun keeps its id. */
    public static List<GitHub.Run> newestFirst(List<GitHub.Run> runs) {
        List<GitHub.Run> sorted = new ArrayList<>(runs);
        sorted.sort(Comparator.comparingLong(GitHub.Run::id).reversed());
        return sorted;
    }

    /**
     * Whether the Build on main has runs and none passed: failed, or still running (a rerun among
     * them). No run at all is not this ({@link #mainNotGreen}).
     */
    public static boolean notGreen(List<GitHub.Run> runs) {
        return !runs.isEmpty() && runs.stream().noneMatch(GitHub.Run::passed);
    }

    /**
     * The run {@code promote} follows when main is at the release commit already: none when one
     * passed (of either kind, as the status counts it) or there is none; else the newest.
     *
     * @param newestFirst the Build's runs on main at the commit, {@link #newestFirst}
     */
    public static Optional<GitHub.Run> runToFollow(List<GitHub.Run> newestFirst) {
        return notGreen(newestFirst) ? Optional.of(newestFirst.get(0)) : Optional.empty();
    }

    /**
     * The line that ends the status and every step: the command for {@link #nextStep}, or that the
     * release is done. For example {@code Next step: java releng/tools/release/Release.java promote}.
     */
    public String nextLine(String tool) {
        String step = nextStep();
        return step.equals("done") ? "Release " + version + " is done." : "Next step: java " + tool + " " + step;
    }
}
