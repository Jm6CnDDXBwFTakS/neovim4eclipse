// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package neovim;

import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import common.Changelog;
import common.ToolFailure;
import common.Version;

/**
 * The Neovim tool's text edits, as plain functions of the files' text ({@code NeovimUpgradeTest}
 * in {@code tests/unit} checks them).
 *
 * <h2>The changelog entry</h2>
 * Moving to a new Neovim changes what users install, so {@code CHANGELOG.md} says so under
 * <i>Unreleased</i>, <i>Changed</i> ({@link Changelog#withChangedEntry}):
 * <pre>
 *   ## [Unreleased]
 *
 *   ### Changed
 *
 *   - *Get Neovim* installs Neovim 0.13.0, which is also the oldest the plug-in runs (it was
 *     0.12.6). After updating the plug-in, Eclipse offers *Get Neovim* at its next start.
 * </pre>
 * What the new Neovim lets the plug-in drop or do better is added by hand, with those changes.
 */
public final class NeovimFiles {

    private NeovimFiles() {
    }

    /** The version in the text of {@code neovim-downloads.properties}. */
    public static Version pinnedVersion(Properties pins) {
        String v = pins.getProperty("version");
        if (v == null) {
            throw new ToolFailure("neovim-downloads.properties has no version=");
        }
        return Version.parse(v.strip());
    }

    /** The changelog entry for a move from {@code from} to {@code to}, without its "- ". */
    public static String entry(Version from, Version to) {
        // *Get Neovim* in italics, as CHANGELOG.md and the README write the plug-in's menu entries.
        return "*Get Neovim* installs Neovim " + to + ", which is also the oldest the plug-in runs (it was " + from
                + "). After updating the plug-in, Eclipse offers *Get Neovim* at its next start.";
    }

    /** How a Neovim move's entry starts ({@link #entry}), in its wording with and without the italics. */
    private static final Pattern ENTRY_START = Pattern.compile("^- \\*?Get Neovim\\*? installs Neovim ");
    private static final Pattern IT_WAS = Pattern.compile("\\(it was (\\d+\\.\\d+\\.\\d+)\\)");

    /**
     * {@code changelog} with the entry for a move to {@code to}. If <i>Unreleased</i> already has
     * a Neovim move's entry (two moves between releases: 0.12.6 to 0.12.7, then to 0.12.8), that
     * one is replaced, keeping its "it was": users go from the released Neovim straight to the new
     * one, and never had the version in between. Otherwise as {@link Changelog#withChangedEntry}.
     */
    public static String withNeovimEntry(String changelog, Version from, Version to) {
        Changelog.Removed earlier = Changelog.withoutEntry(changelog, ENTRY_START);
        Version released = from;
        if (earlier.entry() != null) {
            Matcher was = IT_WAS.matcher(earlier.entry());
            if (was.find()) {
                released = Version.parse(was.group(1));
            }
        }
        return Changelog.withChangedEntry(earlier.changelog(), entry(released, to));
    }
}
