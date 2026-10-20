// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How Neovim should indent new lines in a document ({@code o}, {@code O}, {@code cc}, {@code >>}):
 * with tabs or spaces, how wide one level is, and whether to follow braces.
 *
 * <p><b>Why the plug-in sets this.</b> Neovim indents by its buffer options. The embedded buffers
 * have no filetype (detecting one would also run the user's filetype plugins, which may start
 * language servers inside the embedded Neovim), so only {@code 'autoindent'} applies: {@code o} on a
 * line ending in <code>{</code> copies that line's indent instead of adding a level. So:
 * <ul>
 *   <li>brace languages (Java, C, JavaScript, ...: {@link #BRACE_EXTENSIONS}) get {@code 'cindent'},
 *       which indents inside <code>{ }</code> blocks the way the Eclipse formatter does;</li>
 *   <li>tabs or spaces, and the width of one level, are read from the document itself
 *       ({@link #detect}), so new lines match the code around them; a file with no indented
 *       lines yet falls back to the editor's "insert spaces for tabs" setting.</li>
 * </ul>
 *
 * <p>Example: a Java file indented with four spaces gives {@code expandtab}, {@code shiftwidth=4},
 * {@code cindent}; {@code o} on <code>void f() {</code> then starts the new line four spaces further in.
 *
 * @param useTabs  indent with tab characters ({@code noexpandtab}); otherwise spaces
 * @param width    columns per indent level ({@code shiftwidth})
 * @param braces   follow <code>{ }</code> blocks ({@code cindent})
 */
public record Indentation(boolean useTabs, int width, boolean braces) {

    /** File extensions of languages whose blocks are written in braces, where {@code 'cindent'} fits. */
    static final Set<String> BRACE_EXTENSIONS = Set.of(
            "java", "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "hxx", "cs", "js", "mjs", "cjs", "jsx", "ts",
            "tsx", "kt", "kts", "scala", "groovy", "gradle", "go", "rs", "swift", "php", "dart", "css", "scss",
            "less", "json", "jsonc", "m", "mm", "proto", "glsl", "hlsl");

    /** Most a single indent level can be; anything wider is a continuation or alignment, not a level. */
    private static final int MAX_WIDTH = 8;

    public Indentation {
        width = Math.max(1, Math.min(MAX_WIDTH, width));
    }

    /**
     * {@code 'cinoptions'} for the file: {@code j1} indents Java's anonymous classes, and
     * {@code J1} JavaScript's object literals, correctly; the rest is Vim's default.
     */
    public String cinoptions() {
        return "j1,J1";
    }

    /** Whether {@code fileName} (a path or a name) is in a brace language. */
    public static boolean isBraceLanguage(String fileName) {
        if (fileName == null) {
            return false;
        }
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        if (dot <= slash + 1) {
            return false; // no extension, or a dot file such as ".bashrc"
        }
        return BRACE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * Reads the indentation style from a document's lines.
     *
     * <ul>
     *   <li>Tabs or spaces: whichever starts more indented lines.</li>
     *   <li>Width with spaces: the most common increase of indentation from one non-blank line to
     *       the next (2 for {@code "a"}, {@code "  b"}, {@code "    c"}). Lines of a block comment
     *       ({@code " * text"}, one space further in) are not counted.</li>
     *   <li>Width with tabs: the editor's tab width, as one tab is one level.</li>
     * </ul>
     *
     * @param fileName         used to decide {@link #braces()}
     * @param lines            the document's lines
     * @param tabWidth         the editor's displayed tab width
     * @param spacesByDefault  the editor's "insert spaces for tabs" setting, used when no line is
     *                         indented yet
     */
    public static Indentation detect(String fileName, List<String> lines, int tabWidth, boolean spacesByDefault) {
        int tabLines = 0;
        int spaceLines = 0;
        int[] increases = new int[MAX_WIDTH + 1];
        int previous = -1;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            int spaces = leadingSpaces(line);
            if (line.charAt(0) == '\t') {
                tabLines++;
            } else if (spaces > 0) {
                spaceLines++;
            }
            boolean commentContinuation = line.substring(spaces).startsWith("*");
            if (line.charAt(0) != '\t' && !commentContinuation) {
                if (previous >= 0 && spaces > previous && spaces - previous <= MAX_WIDTH) {
                    increases[spaces - previous]++;
                }
                previous = spaces;
            }
        }
        boolean braces = isBraceLanguage(fileName);
        if (tabLines == 0 && spaceLines == 0) {
            return new Indentation(!spacesByDefault, tabWidth, braces);
        }
        if (tabLines >= spaceLines) {
            return new Indentation(true, tabWidth, braces);
        }
        return new Indentation(false, mostCommon(increases, tabWidth), braces);
    }

    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    /** The index with the highest count (ties: the smaller width); {@code fallback} when all are 0. */
    private static int mostCommon(int[] counts, int fallback) {
        int best = 0;
        for (int width = 1; width < counts.length; width++) {
            if (counts[width] > counts[best]) {
                best = width;
            }
        }
        return best == 0 ? fallback : best;
    }
}
