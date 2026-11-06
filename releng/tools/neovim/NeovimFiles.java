// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package neovim;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import common.ToolFailure;
import common.Version;

/**
 * The Neovim tool's text edits, as plain functions of the files' text ({@code NeovimUpgradeTest}
 * in {@code tests/unit} checks them).
 *
 * <h2>The changelog entry</h2>
 * Moving to a new Neovim changes what users install, so {@code CHANGELOG.md} says so under
 * <i>Unreleased</i>, <i>Changed</i> ({@link #withChangedEntry}):
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

    /** Groups of a Keep a Changelog section, in their order; Changed goes after Added and before the rest. */
    private static final List<String> AFTER_CHANGED = List.of("### Deprecated", "### Removed", "### Fixed", "### Security");

    /** The width the changelog's text is wrapped at. */
    private static final int WIDTH = 100;

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

    /**
     * {@code changelog} with {@code entry} as the first item under <i>Unreleased</i>,
     * <i>Changed</i>: in that group if it exists, else in a new one, after <i>Added</i> and before
     * the other groups. Lines are wrapped at {@link #WIDTH}, continued with two spaces.
     */
    public static String withChangedEntry(String changelog, String entry) {
        String nl = changelog.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(changelog.lines().toList());
        int start = lines.indexOf("## [Unreleased]");
        if (start < 0) {
            throw new ToolFailure("CHANGELOG.md has no line ## [Unreleased]");
        }
        int end = start + 1;
        while (end < lines.size() && !lines.get(end).startsWith("## [")) {
            end++;
        }
        List<String> item = wrap("- " + entry);
        int changed = lines.subList(start, end).indexOf("### Changed");
        if (changed >= 0) {
            int at = start + changed + 1;
            List<String> block = new ArrayList<>(List.of(""));
            block.addAll(item);
            // "### Changed", "", then the items: the new one first, the blank line kept after the heading.
            if (at < lines.size() && lines.get(at).isEmpty()) {
                at++;
                block = new ArrayList<>(item);
            }
            lines.addAll(at, block);
        } else {
            int at = end;
            for (int i = start + 1; i < end; i++) {
                if (AFTER_CHANGED.contains(lines.get(i))) {
                    at = i;
                    break;
                }
            }
            // Back over blank lines, so the group sits after the previous one's last line.
            while (at > start + 1 && lines.get(at - 1).isEmpty()) {
                at--;
            }
            List<String> block = new ArrayList<>(List.of("", "### Changed", ""));
            block.addAll(item);
            lines.addAll(at, block);
        }
        return String.join(nl, lines) + (changelog.endsWith("\n") ? nl : "");
    }

    /** {@code text} as lines of at most {@link #WIDTH} characters, the later ones indented by two spaces. */
    static List<String> wrap(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > WIDTH) {
                out.add(line.toString());
                line = new StringBuilder("  ");
            } else if (line.length() > 0 && !line.toString().equals("  ")) {
                line.append(' ');
            }
            line.append(word);
        }
        out.add(line.toString());
        return out;
    }
}
