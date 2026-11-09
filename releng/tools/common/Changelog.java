// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package common;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The changelog edits the upgrade tools make, as plain functions of {@code CHANGELOG.md}'s text
 * ({@code ChangelogTest} in {@code tests/unit} checks them). An upgrade users notice (a new
 * Neovim, a different Eclipse range) gets an entry under <i>Unreleased</i>, <i>Changed</i>:
 * <pre>
 *   ## [Unreleased]
 *
 *   ### Changed
 *
 *   - Needs Eclipse 2025-12 (4.38) or newer (it was 2024-12 (4.34)).
 * </pre>
 * The file follows <a href="https://keepachangelog.com/">Keep a Changelog</a>: each release's
 * section has groups in a fixed order (Added, Changed, Deprecated, Removed, Fixed, Security).
 */
public final class Changelog {

    /** The file the tools edit, from the repository's root. */
    public static final String FILE = "CHANGELOG.md";

    /** Groups of a Keep a Changelog section, in their order; Changed goes after Added and before the rest. */
    private static final List<String> AFTER_CHANGED = List.of("### Deprecated", "### Removed", "### Fixed", "### Security");

    /** The width the changelog's text is wrapped at. */
    private static final int WIDTH = 100;

    private Changelog() {
    }

    /**
     * {@code changelog} with {@code entry} as the first item under <i>Unreleased</i>,
     * <i>Changed</i>: in that group if it exists, else in a new one, after <i>Added</i> and before
     * the other groups. Lines are wrapped at {@link #WIDTH}, continued with two spaces.
     */
    public static String withChangedEntry(String changelog, String entry) {
        String nl = newline(changelog);
        List<String> lines = new ArrayList<>(changelog.lines().toList());
        int start = unreleased(lines);
        int end = sectionEnd(lines, start);
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

    /**
     * {@code changelog} without the first item under <i>Unreleased</i> whose first line {@code start}
     * finds, and that item's text joined into one line (without its "- "); the text is {@code null}
     * when there is none. A tool run twice between releases (Neovim 0.12.6 to 0.12.7, then to
     * 0.12.8) uses it to replace its first entry, since users go from the released version straight
     * to the newest and never had the one in between. Example: with {@code start}
     * {@code ^- Tested with Eclipse}, the item
     * <pre>
     *   - Tested with Eclipse up to
     *     2026-12 (4.42).
     * </pre>
     * is removed, and its text is {@code Tested with Eclipse up to 2026-12 (4.42).}
     */
    public static Removed withoutEntry(String changelog, Pattern start) {
        String nl = newline(changelog);
        List<String> lines = new ArrayList<>(changelog.lines().toList());
        int section = lines.indexOf("## [Unreleased]");
        if (section >= 0) {
            for (int i = section + 1; i < lines.size() && !lines.get(i).startsWith("## ["); i++) {
                if (start.matcher(lines.get(i)).find()) {
                    int end = i + 1; // the item's continuation lines start with two spaces
                    while (end < lines.size() && lines.get(end).startsWith("  ")) {
                        end++;
                    }
                    String text = String.join(" ", lines.subList(i, end).stream().map(String::strip).toList()).substring(2);
                    lines.subList(i, end).clear();
                    return new Removed(String.join(nl, lines) + (changelog.endsWith("\n") ? nl : ""), text);
                }
            }
        }
        return new Removed(changelog, null);
    }

    /** What {@link #withoutEntry} gives: the changelog's text, and the removed item's, or {@code null}. */
    public record Removed(String changelog, String entry) {
    }

    /** {@code text} as lines of at most {@link #WIDTH} characters, the later ones indented by two spaces. */
    public static List<String> wrap(String text) {
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

    private static String newline(String text) {
        return text.contains("\r\n") ? "\r\n" : "\n";
    }

    private static int unreleased(List<String> lines) {
        int start = lines.indexOf("## [Unreleased]");
        if (start < 0) {
            throw new ToolFailure(FILE + " has no line ## [Unreleased]");
        }
        return start;
    }

    /** The line after the last of the section starting at {@code start}: the next {@code ## [} heading, or the end. */
    private static int sectionEnd(List<String> lines, int start) {
        int end = start + 1;
        while (end < lines.size() && !lines.get(end).startsWith("## [")) {
            end++;
        }
        return end;
    }
}
