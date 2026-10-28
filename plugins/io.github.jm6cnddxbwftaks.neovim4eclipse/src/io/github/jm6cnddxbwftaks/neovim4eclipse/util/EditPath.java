// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Which file {@code :e <file>} means, for a name that reaches Eclipse as typed.
 *
 * <p>Normally Neovim has already turned the name into a full path ({@code LuaScripts.HOST_COMMANDS}:
 * {@code expandcmd()} and {@code fnamemodify(':p')}), the one its own {@code :edit} would open, so
 * this class only passes it on. It is the fallback for a name that arrives relative anyway (a test,
 * a future caller), and follows the same rule as Neovim: a relative name, {@code ./} and
 * {@code ../} included, is relative to the window's working folder. The plug-in sets that folder to
 * the editor's project root ({@code :lcd}), or to the file's folder for a file outside any project,
 * so {@code :e notes.txt} and {@code :w notes.txt} mean the same file. {@code ~} is the home
 * directory; absolute paths are used as they are.
 *
 * <p>Example, editing {@code /ws/app/src/Main.java} in project {@code /ws/app}: {@code :e Util.java}
 * and {@code :e ./Util.java} are {@code /ws/app/Util.java}; the file beside {@code Main.java} is
 * {@code :e %:h/Util.java}, which only Neovim can expand.
 *
 * <p>History: until 0.1.9 a plain name was looked for beside the file first, then in the project
 * root, and {@code ./} meant the file's folder. That came from a time when Neovim's working folder
 * was wherever Eclipse had started. Once each window worked in its project ({@code :lcd}, decision
 * D5 of the Neovim cleanup plan) the reason was gone, and the search made {@code :e} and {@code :w}
 * disagree about the same name.
 */
public final class EditPath {

    private EditPath() {
    }

    /**
     * @param argument     the file name after {@code :e}; quotes are not interpreted
     * @param home         the user's home directory
     * @param windowFolder the window's working folder, for a relative name; {@code null} if unknown
     *                     (then a relative name gives nothing)
     * @return the path, normalized, or {@code null} if {@code argument} is blank, not a valid path on
     *         this system, or relative to a folder that is not known
     */
    public static Path resolve(String argument, Path home, Path windowFolder) {
        if (argument == null || argument.isBlank()) {
            return null;
        }
        String name = argument.strip();
        try {
            if (name.equals("~")) {
                return home.normalize();
            }
            if (name.startsWith("~/") || name.startsWith("~\\")) {
                return home.resolve(name.substring(2)).normalize();
            }
            Path typed = Path.of(name);
            if (typed.isAbsolute()) {
                return typed.normalize();
            }
            return windowFolder == null ? null : windowFolder.resolve(typed).normalize();
        } catch (InvalidPathException e) {
            return null; // a name such as "a\0b" cannot be a file anywhere
        }
    }

    /**
     * {@code file} as Neovim shows a file name in its messages ({@code fnamemodify(name, ':~:.')}):
     * relative to the window's folder when it is inside it, else with {@code ~} for the home
     * directory, else in full. Eclipse gets full paths from Neovim, but {@code :e new.txt} should say
     * {@code "new.txt" [New]}, as Neovim would, not the whole path.
     *
     * <p>Example, window folder {@code /ws/app}, home {@code /home/user}: {@code /ws/app/src/A.java}
     * is {@code src/A.java}, {@code /home/user/notes.txt} {@code ~/notes.txt}, {@code /etc/hosts}
     * {@code /etc/hosts}.
     */
    public static String shown(Path file, Path home, Path windowFolder) {
        Path normal = file.normalize();
        if (windowFolder != null && normal.startsWith(windowFolder.normalize()) && !normal.equals(windowFolder.normalize())) {
            return windowFolder.normalize().relativize(normal).toString();
        }
        if (home != null && normal.startsWith(home.normalize()) && !normal.equals(home.normalize())) {
            return "~" + java.io.File.separator + home.normalize().relativize(normal);
        }
        return normal.toString();
    }
}
