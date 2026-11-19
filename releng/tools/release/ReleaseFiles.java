// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package release;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import common.ToolFailure;
import common.Version;

/**
 * The text edits and rules of a release, as plain functions of the files' text, so that
 * {@code tests/unit} can check them without git, GitHub or a terminal ({@code ReleaseToolTest}).
 *
 * <h2>The changelog</h2>
 * {@code CHANGELOG.md} collects each change under {@code ## [Unreleased]} as it is made. The
 * {@code prepare} step dates that section and opens a new, empty one above it:
 * <pre>
 *   ## [Unreleased]                    ## [Unreleased]
 *                                ⇒
 *   ### Fixed                          ## [0.2.3] - 2026-11-20
 *   - …
 *                                      ### Fixed
 *                                      - …
 * </pre>
 * The Release workflow publishes the dated section as the release notes and stops when it is
 * missing or empty ({@code releng/release-notes.sh}). A section runs from its heading to the next
 * {@code ## [} heading, the same rule as that script's.
 *
 * <h2>The README</h2>
 * Its status line names the version: {@code **Status: 0.2.3, an early release.**}.
 *
 * <h2>A next branch that only sets its version</h2>
 * {@code next} cuts the next release's branch with one commit, the version set by Tycho. When a
 * later {@code next --next 0.3.0} replaces the default {@code release/0.2.31}, that branch may go
 * only if its one commit changes nothing else ({@link #onlySetsTheVersion}).
 */
public final class ReleaseFiles {

    /** The files {@code prepare} changes, the only ones that may differ from the commit the local tests passed on. */
    public static final List<String> RELEASE_NOTES_FILES = List.of("CHANGELOG.md", "README.md");

    static final String UNRELEASED = "## [Unreleased]";

    private static final Pattern STATUS = Pattern.compile("(\\*\\*Status: )(\\d+\\.\\d+\\.\\d+)(, )");

    private ReleaseFiles() {
    }

    /** The lines of the section whose heading starts with {@code heading}, without the heading; {@code null} if there is none. */
    static List<String> section(String changelog, String heading) {
        List<String> lines = changelog.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(heading)) {
                int end = i + 1;
                while (end < lines.size() && !lines.get(end).startsWith("## [")) {
                    end++;
                }
                return lines.subList(i + 1, end);
            }
        }
        return null;
    }

    /** Whether the section exists and holds more than blank lines. */
    private static boolean hasText(List<String> section) {
        return section != null && section.stream().anyMatch(l -> !l.isBlank());
    }

    /** Whether {@code ## [Unreleased]} lists any change. */
    public static boolean unreleasedHasEntries(String changelog) {
        return hasText(section(changelog, UNRELEASED));
    }

    /** Whether the changelog has a section for {@code version} with text in it, as the Release workflow requires. */
    public static boolean hasRelease(String changelog, Version version) {
        // "## [0.2.3]" with its bracket, so that 0.2.3 does not match a later 0.2.30.
        return hasText(section(changelog, "## [" + version + "]"));
    }

    /**
     * The changelog with {@code ## [Unreleased]} dated as {@code version}'s section and a new, empty
     * {@code ## [Unreleased]} above it. Fails when there is nothing to release, or the version's
     * section exists already.
     */
    public static String datedChangelog(String changelog, Version version, LocalDate date) {
        if (section(changelog, "## [" + version + "]") != null) {
            throw new ToolFailure("CHANGELOG.md has a section for " + version + " already");
        }
        if (!unreleasedHasEntries(changelog)) {
            throw new ToolFailure("CHANGELOG.md lists nothing under " + UNRELEASED + "; add the release's changes there first");
        }
        String nl = changelog.contains("\r\n") ? "\r\n" : "\n";
        Matcher m = Pattern.compile("^" + Pattern.quote(UNRELEASED) + "[ \\t]*$", Pattern.MULTILINE).matcher(changelog);
        if (!m.find()) {
            throw new ToolFailure("CHANGELOG.md has no line " + UNRELEASED);
        }
        return changelog.substring(0, m.start()) + UNRELEASED + nl + nl + "## [" + version + "] - " + date + changelog.substring(m.end());
    }

    /** The version the README's status line names; fails unless there is exactly one such line. */
    public static String readmeVersion(String readme) {
        Matcher m = STATUS.matcher(readme);
        if (!m.find()) {
            throw new ToolFailure("README.md has no status line \"**Status: 0.2.3, …\"");
        }
        String found = m.group(2);
        if (m.find()) {
            throw new ToolFailure("README.md has more than one status line \"**Status: …\"");
        }
        return found;
    }

    /** The README with its status line naming {@code version}. */
    public static String readmeWithVersion(String readme, Version version) {
        readmeVersion(readme); // exactly one status line, or a clear failure
        return STATUS.matcher(readme).replaceFirst("$1" + version + "$3");
    }

    /**
     * Whether a local test run on {@code tested} still counts for {@code head}: the same commit, or
     * an earlier one that differs from it only in {@link #RELEASE_NOTES_FILES} ({@code prepare}
     * comes after {@code check}, and its commit changes no code). Any other change, even a comment,
     * needs a new run: a release is tested as it ships.
     *
     * @param testedIsAncestor whether {@code tested} comes before {@code head}
     * @param changed          the files that differ between the two
     */
    public static boolean testsCover(String tested, String head, boolean testedIsAncestor, List<String> changed) {
        if (tested == null || tested.isEmpty()) {
            return false;
        }
        return tested.equals(head) || testedIsAncestor && RELEASE_NOTES_FILES.containsAll(changed);
    }

    /** Lines a diff's header gives for a change that is not an edit of lines: a new, deleted, renamed or binary file, a mode. */
    private static final List<String> NOT_A_LINE_EDIT = List.of("new file", "deleted file", "rename ", "copy ", "similarity ",
            "dissimilarity ", "old mode", "new mode", "Binary files", "GIT binary patch", "Submodule ");

    /**
     * Whether {@code diff} ({@code git diff -U0 <main> <branch>}) only moves the version from
     * {@code from} (the release on {@code main}) to {@code to} (the branch's version, as
     * {@code -SNAPSHOT} or {@code .qualifier}). Tycho's {@code set-version} changes one line for one
     * line, so every hunk must remove as many lines as it adds, and its n-th removed line, with
     * {@code from} taken out, must equal its n-th added line with {@code to} taken out. Nothing
     * else may change: no other line, no file added, deleted, renamed or made binary, no mode, no
     * final newline. The check reads the commit's content, not its message, so a branch that holds
     * any other work is kept.
     *
     * <p>Example: {@code -  <version>0.2.30-SNAPSHOT</version>} and
     * {@code +  <version>0.2.31-SNAPSHOT</version>} in one hunk pass; a changed dependency version
     * ({@code 3.7.0} to {@code 3.8.0}), a removed {@code -- comment} line (in the diff:
     * {@code --- comment}), or a line moved to another place does not. An empty diff is not a
     * version commit either.
     */
    public static boolean onlySetsTheVersion(String diff, Version from, Version to) {
        Pattern fromText = versionText(from);
        Pattern toText = versionText(to);
        List<String> removed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        boolean inHeader = false; // between "diff --git" and a file's first "@@": "---"/"+++" name the files there
        int edits = 0;
        for (String line : diff.lines().toList()) {
            if (line.startsWith("diff --git ")) {
                if (!samePairs(removed, added)) {
                    return false;
                }
                inHeader = true;
            } else if (line.startsWith("@@")) {
                if (!samePairs(removed, added)) {
                    return false;
                }
                inHeader = false;
            } else if (inHeader) {
                if (NOT_A_LINE_EDIT.stream().anyMatch(line::startsWith)) {
                    return false;
                }
                // index, --- a/…, +++ b/…: the file's names and blobs
            } else if (line.startsWith("-")) {
                removed.add(fromText.matcher(line.substring(1)).replaceAll("<version>"));
                edits++;
            } else if (line.startsWith("+")) {
                added.add(toText.matcher(line.substring(1)).replaceAll("<version>"));
            } else {
                return false; // "\ No newline at end of file", or anything else -U0 does not print for a line edit
            }
        }
        return edits > 0 && samePairs(removed, added);
    }

    /** Whether one hunk's removed and added lines pair up in order; empties both for the next hunk. */
    private static boolean samePairs(List<String> removed, List<String> added) {
        boolean same = removed.equals(added);
        removed.clear();
        added.clear();
        return same;
    }

    /** {@code version} as Maven or OSGi writes it, not inside a longer number: 0.2.3 is not part of 0.2.30. */
    private static Pattern versionText(Version version) {
        return Pattern.compile("(?<![\\d.])" + Pattern.quote(version.toString()) + "(?:-SNAPSHOT|\\.qualifier)?(?![\\d.])");
    }
}
