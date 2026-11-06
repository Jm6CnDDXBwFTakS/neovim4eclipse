// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Neovim version ({@code major.minor.patch}) and the oldest one this plug-in runs on.
 *
 * <h2>Why the plug-in checks</h2>
 * The plug-in relies on Neovim's newer APIs instead of imitating them (docs/UPGRADING.md, "Neovim").
 * An older Neovim would not fail cleanly: a Lua function or option it lacks makes a script error
 * out somewhere later, or a start-up request time out. So right after connecting, the session asks
 * Neovim for {@code vim.version()} and refuses to go on below {@link #MINIMUM}, with a message
 * that says which version was found and what is needed:
 *
 * <pre>
 *   NeovimVersion.fromLua(List.of(0, 12, 4)).isSupported()   false
 *   NeovimVersion.fromLua(List.of(0, 12, 6)).isSupported()   true
 *   NeovimVersion.fromLua(List.of(0, 13, 0)).isSupported()   true
 * </pre>
 *
 * <p>{@link #MINIMUM} is the release in {@code neovim-downloads.properties} ({@code version=}), the
 * data file behind <i>Get Neovim</i>: the one place the version is written. So the Neovim the
 * plug-in installs, the one CI runs ({@code java releng/UpdateNeovimDownloads.java install}
 * downloads it), and the oldest it accepts are always the same. The docs' copies of the number are
 * listed in {@code releng/version-copies.txt}, which {@code java releng/tools/neovim/NeovimUpgrade.java}
 * rewrites and {@code VersionConsistencyTest} checks. A pre-release ({@code 0.13.0-dev}) counts as its
 * numbers, so a nightly build newer than the minimum is accepted.
 *
 * @param major the first number, 0 for every Neovim so far
 * @param minor the second number, the release line (0.12)
 * @param patch the third number, the bug-fix release (0.12.6)
 */
public record NeovimVersion(int major, int minor, int patch) implements Comparable<NeovimVersion> {

    /**
     * Lua that returns the running Neovim's version as three numbers, e.g. {@code {0, 12, 6}}.
     * {@code vim.version()} has existed since Neovim 0.8; on anything older the call fails, which
     * the caller reports as too old as well.
     */
    public static final String LUA_QUERY = "local v = vim.version() return { v.major, v.minor, v.patch }";

    /** The start of {@code nvim --version}'s first line, {@code NVIM v0.12.6}; the numbers are groups. */
    private static final Pattern VERSION_LINE = Pattern.compile("^\\s*NVIM v(\\d+)\\.(\\d+)\\.(\\d+)");

    /**
     * The oldest supported Neovim, and the one <i>Get Neovim</i> installs: the version in
     * {@code neovim-downloads.properties}. It moves with each Neovim release, a week or so after it
     * appears ({@code java releng/tools/neovim/NeovimUpgrade.java}, docs/UPGRADING.md).
     *
     * <p>Declared after {@link #VERSION_LINE} on purpose: reading the data file parses its version
     * with {@link #fromVersionOutput}, which needs that pattern, and static fields are set in the
     * order they are written.
     *
     * <p>A missing or broken data file stops this class from loading at all (the cause names the
     * file, {@link NeovimRelease#bundled}), where it once failed only <i>Get Neovim</i>. That is
     * deliberate: the plug-in cannot know which Neovim it supports without it, and the file ships
     * inside the plug-in. {@code VersionConsistencyTest} and {@code NeovimReleaseTest} read the
     * shipped file in every build, so a broken one never leaves the repository.
     */
    public static final NeovimVersion MINIMUM = NeovimRelease.bundled().version();

    /**
     * Reads the answer to {@link #LUA_QUERY}: a list of three numbers (msgpack integers arrive as
     * {@code Long} or {@code Integer}).
     *
     * @throws IllegalArgumentException if the answer is not three numbers
     */
    public static NeovimVersion fromLua(Object answer) {
        if (answer instanceof List<?> parts && parts.size() == 3
                && parts.stream().allMatch(Number.class::isInstance)) {
            return new NeovimVersion(((Number) parts.get(0)).intValue(), ((Number) parts.get(1)).intValue(),
                    ((Number) parts.get(2)).intValue());
        }
        throw new IllegalArgumentException("Unexpected answer to vim.version(): " + answer);
    }

    /**
     * Reads the first line of {@code nvim --version}: {@code NVIM v0.12.6}, or for a nightly build
     * {@code NVIM v0.13.0-dev-1234+gabcdef}, which counts as 0.13.0.
     *
     * @return the version, or {@code null} if the text does not start that way (not Neovim)
     */
    public static NeovimVersion fromVersionOutput(String output) {
        if (output == null) {
            return null;
        }
        Matcher m = VERSION_LINE.matcher(output);
        if (!m.find()) {
            return null;
        }
        return new NeovimVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)));
    }

    /** Whether this version is {@link #MINIMUM} or newer. */
    public boolean isSupported() {
        return compareTo(MINIMUM) >= 0;
    }

    /**
     * Why this version is refused, for the "Neovim not started" notice. Example:
     * {@code Neovim 0.12.4 is too old: Neovim for Eclipse needs 0.12.6 or newer.}
     */
    public String tooOldMessage() {
        return "Neovim " + this + " is too old: Neovim for Eclipse needs " + MINIMUM + " or newer.";
    }

    @Override
    public int compareTo(NeovimVersion other) {
        int c = Integer.compare(major, other.major);
        if (c == 0) {
            c = Integer.compare(minor, other.minor);
        }
        return c != 0 ? c : Integer.compare(patch, other.patch);
    }

    /** {@code 0.12.6}, as {@code nvim --version} prints it without the {@code v}. */
    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
