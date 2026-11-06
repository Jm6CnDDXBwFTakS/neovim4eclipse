// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A release version, {@code <major>.<minor>.<patch>} as in {@code 0.2.3}: the only form the Release
 * workflow accepts in a tag ({@code v0.2.3}; see {@code .github/workflows/release.yml}). The names
 * that follow from it live here, so that every tool spells them the same way:
 * <pre>
 *   Version v = Version.parse("0.2.3");
 *   v.branch()     release/0.2.3     the branch the release is made from
 *   v.tag()        v0.2.3            the tag that starts the Release workflow
 *   v.snapshot()   0.2.3-SNAPSHOT    the version in the POMs while it is being made
 *   v.nextPatch()  0.2.4             the release after it, by default
 * </pre>
 */
public record Version(int major, int minor, int patch) implements Comparable<Version> {

    private static final Pattern FORM = Pattern.compile("(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})");

    /** {@code text} as a version; fails on anything but three plain numbers ({@code 0.2}, {@code v0.2.3}, {@code 0.2.03}). */
    public static Version parse(String text) {
        Matcher m = FORM.matcher(text);
        if (!m.matches()) {
            throw new ToolFailure("not a version of the form 0.2.3: " + text);
        }
        return new Version(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    }

    /** The version of a POM's {@code 0.2.3-SNAPSHOT}; fails on a version without {@code -SNAPSHOT}. */
    public static Version ofSnapshot(String pomVersion) {
        if (!pomVersion.endsWith("-SNAPSHOT")) {
            throw new ToolFailure("the POM version " + pomVersion + " is not a -SNAPSHOT; a release branch carries one (CONTRIBUTING.md, \"Releasing\")");
        }
        return parse(pomVersion.substring(0, pomVersion.length() - "-SNAPSHOT".length()));
    }

    public Version nextPatch() {
        return new Version(major, minor, patch + 1);
    }

    public String branch() {
        return "release/" + this;
    }

    public String tag() {
        return "v" + this;
    }

    public String snapshot() {
        return this + "-SNAPSHOT";
    }

    @Override
    public int compareTo(Version other) {
        int c = Integer.compare(major, other.major);
        if (c == 0) {
            c = Integer.compare(minor, other.minor);
        }
        return c != 0 ? c : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
