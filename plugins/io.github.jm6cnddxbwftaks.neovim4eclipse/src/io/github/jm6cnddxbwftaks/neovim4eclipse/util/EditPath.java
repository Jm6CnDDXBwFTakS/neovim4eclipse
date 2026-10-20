// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Where {@code :e <file>} looks for {@code <file>}.
 *
 * <p>Vim resolves a relative name against its working directory. An embedded Neovim's working
 * directory is wherever Eclipse was started, which means nothing to the user, so names are resolved
 * against places that do: first the directory of the file being edited, then the root of its
 * project. {@code ~} is the home directory; absolute paths are used as they are.
 *
 * <p>Example: editing {@code /ws/app/src/Main.java} in project {@code /ws/app},
 * {@code :e Util.java} tries {@code /ws/app/src/Util.java}, then {@code /ws/app/Util.java}.
 *
 * <p>A name starting with {@code ./} or {@code ../} is resolved against the first directory only
 * (the edited file's): {@code :e ./Util.java} means the one beside {@code Main.java}, never the
 * project root's. This is the plug-in's rule, not Vim's (Vim has no second directory to opt out
 * of); it works like a shell, where {@code ./script} runs the file here and {@code script} searches
 * {@code PATH}. {@code ../} is not limited to the project: from {@code src/}, {@code ../../x} is
 * {@code /ws/x}.
 */
public final class EditPath {

    private EditPath() {
    }

    /**
     * @param argument the file name as typed after {@code :e}; quotes are not interpreted
     * @param home     the user's home directory
     * @param bases    directories to resolve a relative name against, in order; {@code null}
     *                 entries are skipped. A {@code ./} or {@code ../} name uses only the first
     *                 entry, and gives nothing when it is {@code null}
     * @return candidate paths, normalized and without duplicates, best first; empty if
     *         {@code argument} is blank or not a valid path on this system
     */
    public static List<Path> candidates(String argument, Path home, List<Path> bases) {
        List<Path> out = new ArrayList<>();
        if (argument == null || argument.isBlank()) {
            return out;
        }
        String name = argument.strip();
        try {
            if (name.equals("~")) {
                out.add(home.normalize());
                return out;
            }
            if (name.startsWith("~/") || name.startsWith("~\\")) {
                out.add(home.resolve(name.substring(2)).normalize());
                return out;
            }
            Path typed = Path.of(name);
            if (typed.isAbsolute()) {
                out.add(typed.normalize());
                return out;
            }
            List<Path> searched = isFromCurrentFolder(name) && !bases.isEmpty()
                    ? bases.subList(0, 1) : bases;
            for (Path base : searched) {
                if (base != null) {
                    Path candidate = base.resolve(typed).normalize();
                    if (!out.contains(candidate)) {
                        out.add(candidate);
                    }
                }
            }
        } catch (InvalidPathException e) {
            out.clear(); // a name such as "a\0b" cannot be a file anywhere
        }
        return out;
    }

    /**
     * Whether {@code argument} starts with {@code ./} or {@code ../} ({@code .\} and {@code ..\} too,
     * for Windows users), so it is resolved against the edited file's folder only. A bare {@code .}
     * or {@code ..} counts as well.
     */
    public static boolean isFromCurrentFolder(String argument) {
        if (argument == null) {
            return false;
        }
        String name = argument.strip();
        return name.equals(".") || name.equals("..")
                || name.startsWith("./") || name.startsWith(".\\")
                || name.startsWith("../") || name.startsWith("..\\");
    }
}
