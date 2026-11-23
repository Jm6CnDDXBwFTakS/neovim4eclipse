// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

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
 * was wherever Eclipse had started. Once each window worked in its project ({@code :lcd}) the
 * reason was gone, and the search made {@code :e} and {@code :w}
 * disagree about the same name.
 */
public final class EditPath {

    /** Linux's folders of kernel files, which say they are regular files of size 0. */
    private static final List<String> KERNEL_FOLDERS = List.of("/proc", "/sys");

    /**
     * The names Windows keeps for its devices, in any case and in every folder, as Microsoft lists
     * them in "Naming Files, Paths, and Namespaces", with the console's {@code CONIN$} and
     * {@code CONOUT$}. The digits include the superscripts 1, 2 and 3, which Windows also accepts.
     */
    private static final Pattern WINDOWS_DEVICE = Pattern
            .compile("(?i)CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|(?:COM|LPT)[0-9\u00B9\u00B2\u00B3]");

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
     * Why an editor cannot open {@code file} as the message to show, or {@code null} for a file an
     * editor can read or a missing one it can create. The check of {@code :e} and {@code :split},
     * made before they look for the file: Java calls a Windows device missing, so {@code :e NUL}
     * would otherwise open a blank new file whose save goes to the device. Links are followed for
     * what the file is; a Windows device is told by the name given, so {@code NewFiles.open} asks
     * again for the target of a dangling link ({@code notes.txt -> COM1}).
     *
     * @param shown the name for the message ({@link #shown})
     */
    public static String whyNotOpened(Path file, String shown) {
        return whyNotOpened(file, shown, FilePaths.Platform.current());
    }

    /** {@link #whyNotOpened(Path, String)} under {@code platform}'s rule. */
    static String whyNotOpened(Path file, String shown, FilePaths.Platform platform) {
        if (Files.isDirectory(file)) {
            return "\"" + shown + "\" is a directory";
        }
        // A name with an extension may be a real file on Windows 11: it opens if it exists, a cloud
        // drive's too, which Java calls "other" than regular (see mayNeverEnd). A bare device name
        // never is one, and a port that is present may well be reported as existing.
        String path = file.toString();
        boolean device = platform.windows() && isWindowsDevice(path)
                && (isAlwaysWindowsDevice(path) || !Files.exists(file));
        return device || mayNeverEnd(file, platform) ? "\"" + shown + "\" is not a file" : null;
    }

    /**
     * Whether Windows may take {@code path} for a device, whatever its folder: its last name is a
     * reserved one ({@link #WINDOWS_DEVICE}), alone or before an extension, or it is in the device
     * namespace ({@code \\.\}). Examples: {@code C:\work\NUL}, {@code con.txt},
     * {@code \\.\COM1}; not {@code NULL.txt} or {@code console}.
     *
     * <p>Judged by name because nothing else tells: Java reports {@code NUL} and {@code CON} as
     * missing files. The extension counts because Windows before 11 opens the device for
     * {@code NUL.txt}. So a caller lets an existing file with an extension through, as Windows 11
     * allows them ({@link #isAlwaysWindowsDevice}), and what this costs is only creating a new
     * {@code NUL.txt} with {@code :e}.
     */
    static boolean isWindowsDevice(String path) {
        if (inDeviceNamespace(path)) {
            return true;
        }
        String name = lastName(path);
        int dot = name.indexOf('.');
        // Windows ignores spaces before the extension or at the end: "NUL .txt" is NUL.
        return WINDOWS_DEVICE.matcher((dot < 0 ? name : name.substring(0, dot)).stripTrailing()).matches();
    }

    /**
     * Whether {@code path} is a device on every Windows, so that no file can be meant: it is in the
     * device namespace, or its last name is a reserved one with no extension, trailing dots and
     * spaces ignored as Windows ignores them ({@code NUL}, {@code NUL.}, {@code COM1 }).
     */
    static boolean isAlwaysWindowsDevice(String path) {
        if (inDeviceNamespace(path)) {
            return true;
        }
        return WINDOWS_DEVICE.matcher(lastName(path).replaceAll("[. ]+$", "")).matches();
    }

    private static boolean inDeviceNamespace(String path) {
        return path.startsWith("\\\\.\\") || path.startsWith("//./");
    }

    /** The name after the last separator; a colon ends a drive, as in {@code C:NUL}. */
    private static String lastName(String path) {
        return path.substring(Math.max(Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/')), path.lastIndexOf(':')) + 1);
    }

    /**
     * Whether reading {@code file}, links followed, may never end, so that no editor may open it: a
     * device, a pipe, a socket, or a kernel file under {@code /proc} or {@code /sys}.
     *
     * <p>Why: an editor loads its whole file on the UI thread (Eclipse's file buffers call
     * {@code readAllBytes}). A cloned repository's {@code notes.txt -> /dev/zero} has no end, so it
     * would stall Eclipse until its memory ran out and the load failed. A pipe would hold it until
     * a writer came. {@code /proc/self/pagemap} is as endless, though the system calls it a regular
     * file of size 0, so those two folders are refused by where the path really ends. That costs
     * {@code :e /proc/cpuinfo}, which does end: nothing tells the two kinds apart without reading.
     *
     * <p>What this stops: whatever would reach Eclipse. Neovim refuses a device itself ("is not a
     * file") but keeps a buffer of that name, which a jump ({@code gf}, a picker's Enter) would
     * then have Eclipse open, so it is checked here. On a pipe or an endless kernel file, a jump or
     * a plug-in's window runs Neovim's own {@code :edit}, which keeps reading, as in a terminal,
     * before Eclipse hears of it; only {@code :e} and {@code :split}, which Eclipse carries out,
     * are stopped here.
     *
     * <p>Never on Windows: Java calls every reparse point "other" than a regular file, which may
     * include ordinary files a cloud drive or deduplication keeps that way, and those must still
     * open. Devices there are told by name ({@link #isWindowsDevice}). A missing file and a folder
     * are not this case either; each has its own rule.
     */
    public static boolean mayNeverEnd(Path file) {
        return mayNeverEnd(file, FilePaths.Platform.current());
    }

    /** {@link #mayNeverEnd(Path)} under {@code platform}'s rule. */
    static boolean mayNeverEnd(Path file, FilePaths.Platform platform) {
        if (platform.windows() || !Files.exists(file) || Files.isDirectory(file)) {
            return false;
        }
        return !Files.isRegularFile(file)
                || KERNEL_FOLDERS.stream().anyMatch(folder -> FilePaths.inside(file.toString(), folder, platform));
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
        return shown(file, home, windowFolder, FilePaths.Platform.current());
    }

    /**
     * {@link #shown(Path, Path, Path)} under {@code platform}'s rule for "inside" ({@link FilePaths}:
     * case ignored on Windows and macOS, links resolved), so a window folder {@code /ws/App} still
     * shortens {@code /ws/app/src/A.java} to {@code src/A.java} on macOS. The part shown keeps the
     * file's own spelling, with the system's separators.
     */
    static String shown(Path file, Path home, Path windowFolder, FilePaths.Platform platform) {
        Path normal = file.normalize();
        String below = windowFolder == null ? null : FilePaths.below(normal.toString(), windowFolder.toString(), platform);
        if (below != null) {
            return Path.of(below).toString();
        }
        below = home == null ? null : FilePaths.below(normal.toString(), home.toString(), platform);
        if (below != null) {
            return "~" + java.io.File.separator + Path.of(below);
        }
        return normal.toString();
    }
}
