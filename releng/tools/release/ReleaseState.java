// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package release;

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
 * @param tagged     origin has the version's tag
 * @param released   the GitHub Release has the ZIP and its signature
 * @param nextCut    origin has the next release branch
 */
public record ReleaseState(Version version, boolean tested, boolean prepared, boolean onMain, boolean tagged,
        boolean released, boolean nextCut) {

    /** The step to run next, or {@code "done"}. */
    public String nextStep() {
        if (tagged) {
            // tag again until the Release has its files: it watches the run and verifies them.
            return !released ? "tag" : nextCut ? "done" : "next";
        }
        if (onMain) {
            return "tag";
        }
        if (!tested) {
            return "check";
        }
        return prepared ? "promote" : "prepare";
    }
}
