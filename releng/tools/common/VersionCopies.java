// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@code releng/version-copies.txt}: where each version number is copied by hand into text Maven
 * cannot fill in. One reader for the tools that rewrite the copies
 * ({@code releng/tools/neovim/NeovimUpgrade.java}) and for {@code VersionConsistencyTest}, which
 * checks them, so the two cannot disagree about what a copy is. The file's format:
 * <pre>
 *   [neovim]
 *   README.md  \bNeovim (\d+\.\d+\.\d+)
 * </pre>
 * a section per version, then a file and a regular expression whose first group is the version.
 * For example, with that line, {@link #rewrite} turns README's {@code Neovim 0.12.6} into
 * {@code Neovim 0.13.0}, and leaves {@code checked on 0.12.6} alone.
 */
public final class VersionCopies {

    /** The repository path of the list. */
    public static final String FILE = "releng/version-copies.txt";

    /** One copy: a file, and where in it the version stands (group 1). */
    public record Copy(String file, Pattern pattern) {
    }

    private VersionCopies() {
    }

    /** The copies by section, in file order; fails on a malformed line. */
    public static Map<String, List<Copy>> parse(String text) {
        Map<String, List<Copy>> sections = new LinkedHashMap<>();
        List<Copy> current = null;
        int number = 0;
        for (String line : text.lines().toList()) {
            number++;
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            if (l.startsWith("[") && l.endsWith("]")) {
                current = sections.computeIfAbsent(l.substring(1, l.length() - 1).strip(), k -> new ArrayList<>());
                continue;
            }
            String[] parts = l.split("\\s+", 2);
            if (current == null || parts.length != 2) {
                throw new ToolFailure(FILE + ":" + number + ": expected \"<file>  <pattern>\" inside a [section]: " + line);
            }
            Pattern pattern;
            try {
                pattern = Pattern.compile(parts[1]);
            } catch (PatternSyntaxException e) {
                throw new ToolFailure(FILE + ":" + number + ": not a regular expression: " + e.getDescription());
            }
            if (pattern.matcher("").groupCount() < 1) {
                throw new ToolFailure(FILE + ":" + number + ": the pattern has no group for the version: " + parts[1]);
            }
            current.add(new Copy(parts[0], pattern));
        }
        return sections;
    }

    /** The versions {@code copy}'s pattern finds in {@code text}, in order. */
    public static List<String> versionsIn(String text, Copy copy) {
        List<String> found = new ArrayList<>();
        Matcher m = copy.pattern().matcher(text);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    /** {@code text} with every version {@code copy}'s pattern finds replaced by {@code version}; the rest as it was. */
    public static String rewrite(String text, Copy copy, String version) {
        Matcher m = copy.pattern().matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start(1)).append(version);
            last = m.end(1);
        }
        return out.append(text.substring(last)).toString();
    }
}
