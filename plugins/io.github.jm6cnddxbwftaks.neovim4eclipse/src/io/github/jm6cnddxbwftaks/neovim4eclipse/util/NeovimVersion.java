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
 * <p>{@link #MINIMUM} is the single source of truth for the supported version: CI runs exactly that
 * Neovim ({@code NVIM_VERSION} in {@code .github/workflows/build.yml}), and the docs name it;
 * {@code VersionConsistencyTest} checks both. A pre-release ({@code 0.13.0-dev}) counts as its
 * numbers, so a nightly build newer than the minimum is accepted.
 *
 * @param major the first number, 0 for every Neovim so far
 * @param minor the second number, the release line (0.12)
 * @param patch the third number, the bug-fix release (0.12.6)
 */
public record NeovimVersion(int major, int minor, int patch) implements Comparable<NeovimVersion> {

    /**
     * The oldest supported Neovim. Raise it whenever a newer release lets Neovim do something the
     * plug-in would otherwise work around, or fixes a security issue (docs/UPGRADING.md).
     */
    public static final NeovimVersion MINIMUM = new NeovimVersion(0, 12, 6);

    /**
     * Lua that returns the running Neovim's version as three numbers, e.g. {@code {0, 12, 6}}.
     * {@code vim.version()} has existed since Neovim 0.8; on anything older the call fails, which
     * the caller reports as too old as well.
     */
    public static final String LUA_QUERY = "local v = vim.version() return { v.major, v.minor, v.patch }";

    /** The start of {@code nvim --version}'s first line, {@code NVIM v0.12.6}; the numbers are groups. */
    private static final Pattern VERSION_LINE = Pattern.compile("^\\s*NVIM v(\\d+)\\.(\\d+)\\.(\\d+)");

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
