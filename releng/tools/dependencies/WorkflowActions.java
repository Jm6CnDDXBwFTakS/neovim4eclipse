// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package dependencies;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import common.ToolFailure;

/**
 * The GitHub Actions the workflows use, as pure functions over the workflow files' text. Each
 * action is pinned to a full commit SHA, with the release it is as a comment
 * (docs/UPGRADING.md, "GitHub Actions and the runner"):
 * <pre>
 *   - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
 * </pre>
 * A tag such as {@code v7} can be moved by whoever controls the action's repository, a commit
 * cannot. A move rewrites the SHA and the comment together; the release is read from the
 * comment, so a line without one, or with a tag in place of a SHA, stops the tool.
 */
public final class WorkflowActions {

    /** The folder of the workflow files. */
    public static final String FOLDER = ".github/workflows";

    /** One {@code uses:} line of an action: {@code owner/repo} (an action in a folder of it too), the SHA, the release. */
    public record Use(String file, int line, String action, String sha, String release) {
        /** {@code owner/repo}, the action's repository on GitHub and its name in {@code dependabot.yml}. */
        public String repository() {
            String[] p = action.split("/");
            return p[0] + "/" + p[1];
        }
    }

    /** A {@code uses:} line: what stands before the reference, the reference, and the rest of the line. */
    private static final Pattern USES = Pattern.compile("^(\\s*(?:-\\s+)?uses:\\s*)(\\S+)(.*)$");
    /** The rest: a comment whose first word is the release, and anything after it ({@code # v7.0.1 (for Windows)}). */
    private static final Pattern COMMENT = Pattern.compile("\\s+#\\s*(\\S+)(.*)");
    private static final Pattern PINNED = Pattern.compile("([\\w.-]+/[\\w.-]+(?:/[\\w./-]+)?)@([0-9a-f]{40})");
    private static final Pattern RELEASE = Pattern.compile("v?\\d+(\\.\\d+){0,3}");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    private WorkflowActions() {
    }

    /**
     * The actions {@code text} (one workflow file) uses. A workflow of the repository itself
     * ({@code uses: ./.github/workflows/build.yml}) is not an action and is skipped; any other
     * {@code uses:} must be pinned as above.
     */
    public static List<Use> uses(String file, String text) {
        List<Use> out = new ArrayList<>();
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = USES.matcher(lines.get(i));
            if (!m.matches()) {
                continue;
            }
            String ref = m.group(2);
            if (ref.startsWith("./")) {
                continue;
            }
            Matcher p = PINNED.matcher(ref);
            String where = file + ":" + (i + 1);
            if (!p.matches()) {
                throw new ToolFailure(where + ": " + ref + " is not pinned to a commit SHA (owner/repo@<40 hex digits> # vX.Y.Z)");
            }
            Matcher c = COMMENT.matcher(m.group(3).stripTrailing());
            String release = c.matches() ? c.group(1) : null;
            if (release == null || !RELEASE.matcher(release).matches()) {
                throw new ToolFailure(where + ": " + ref + " has no \"# vX.Y.Z\" comment naming its release");
            }
            out.add(new Use(file, i + 1, p.group(1), p.group(2), release));
        }
        return out;
    }

    /**
     * {@code text} with every line that uses {@code repository} (any of its folders) at
     * {@code fromSha} moved to {@code toSha} and the comment {@code toRelease}. Example:
     * {@code actions/setup-java@b843ebf… # v6.1.0} becomes {@code actions/setup-java@<new sha> # v6.2.0};
     * more words after the release in the comment stay.
     */
    public static String moved(String file, String text, String repository, String fromSha, String toSha, String toRelease) {
        if (!SHA.matcher(toSha).matches() || !RELEASE.matcher(toRelease).matches()) {
            throw new ToolFailure("not a commit SHA and a release: " + toSha + " " + toRelease); // GitHub's answer, checked
        }
        List<String> lines = new ArrayList<>(text.lines().toList());
        for (Use u : uses(file, text)) {
            if (u.repository().equals(repository) && u.sha().equals(fromSha)) {
                String line = lines.get(u.line() - 1);
                Matcher m = USES.matcher(line);
                Matcher c = m.matches() ? COMMENT.matcher(m.group(3).stripTrailing()) : null;
                if (c == null || !c.matches()) {
                    throw new IllegalStateException(line); // uses() read it as a pinned line
                }
                lines.set(u.line() - 1, m.group(1) + u.action() + "@" + toSha + " # " + toRelease + c.group(2));
            }
        }
        String out = String.join("\n", lines);
        return text.endsWith("\n") ? out + "\n" : out;
    }

    /** A release tag and when it was published, as {@code gh api …/releases} lists it ({@code v6.2.0\t2026-11-04T22:12:26Z}). */
    public record Tag(String name, String published) {
        /** The release number the tag names, or empty for one that is not a plain release. */
        public Optional<ReleaseNumber> number() {
            return RELEASE.matcher(name).matches() ? ReleaseNumber.parse(name) : Optional.empty();
        }
    }

    /** {@code gh}'s tab-separated lines as tags; lines that are not one are skipped. */
    public static List<Tag> tags(String ghLines) {
        List<Tag> out = new ArrayList<>();
        for (String line : ghLines.lines().toList()) {
            String[] f = line.split("\t", -1);
            if (f.length == 2 && !f[0].isBlank()) {
                out.add(new Tag(f[0].strip(), f[1].strip()));
            }
        }
        return out;
    }

    /**
     * The commit a release tag points to now, and that commit's date, as
     * {@code gh api repos/<owner>/<repo>/commits/refs/tags/<tag> --jq '[.sha, .commit.committer.date] | @tsv'}
     * answers: {@code 77dd5b2c…\t2026-11-04T21:58:10Z}.
     */
    public record TagCommit(String sha, Instant date) {

        /** {@code gh}'s answer, checked: a full SHA and a date, else the tool stops. */
        public static TagCommit parse(String ghLine, String what) {
            String[] f = ghLine.strip().split("\t", -1);
            if (f.length != 2 || !SHA.matcher(f[0]).matches()) {
                throw new ToolFailure("GitHub gave no commit SHA and date for " + what + ": " + ghLine.strip());
            }
            try {
                return new TagCommit(f[0], Instant.parse(f[1]));
            } catch (DateTimeParseException e) {
                throw new ToolFailure("GitHub gave no commit date for " + what + ": " + f[1]);
            }
        }
    }

    /** How much later than its release a tag's commit may be dated: committers' clocks are not exact. */
    static final Duration CLOCK_SLACK = Duration.ofHours(1);

    /**
     * Whether a tag looks moved: its commit is dated after the release it belongs to came out. A
     * release is made from a commit that exists, so its tag's commit is older; one that is newer
     * was pointed at later, by whoever controls the action's repository, and the week the release
     * has been out says nothing about that commit. Example: v6.2.0 published 2026-11-04 but
     * pointing to a commit of 2026-11-10.
     *
     * <p>The commit's date is written by its author's git, so whoever controls the repository can
     * backdate one and pass: this catches a tag moved by mistake or carelessly, not one moved to
     * deceive. GitHub keeps no record of when a tag was moved that the tool could read instead.
     */
    public static boolean looksMoved(Instant released, Instant committed) {
        return committed.isAfter(released.plus(CLOCK_SLACK));
    }
}
