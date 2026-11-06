// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package release;

import java.time.LocalDate;
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
}
