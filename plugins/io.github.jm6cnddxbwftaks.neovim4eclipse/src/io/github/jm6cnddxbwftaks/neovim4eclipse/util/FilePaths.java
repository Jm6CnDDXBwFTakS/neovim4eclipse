// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * The one rule for comparing file paths in Eclipse's half of the plug-in: whether two names are one
 * file, or a path is inside a folder. Neovim's half has the same rule in Lua
 * ({@code LuaScripts.PATHS}, module {@code neovim4eclipse.paths}); both are checked against one table
 * of cases ({@code tests/unit/src/test/resources/path-cases.tsv}: {@code FilePathsTest} here,
 * {@code paths_spec.lua} there), so the two halves cannot disagree about a file.
 *
 * <h2>Why a rule of its own</h2>
 * {@link Path#equals} and {@link Path#startsWith} compare the names as written, with the running
 * system's idea of case: on Windows they ignore it, on macOS they do not, although macOS's file
 * system (APFS, by default) does. Neither resolves links. But one file reaches the plug-in in many
 * spellings: {@code C:\WS\a.txt} and {@code c:/ws/A.TXT} on Windows, {@code /var/...} and its link
 * target {@code /private/var/...} on macOS, a Windows junction or 8.3 short name
 * ({@code C:\PROGRA~1}), {@code ..} and doubled slashes.
 *
 * <h2>The rule</h2>
 * {@link #canonical}: the path absolute, with {@code /} separators and {@code .} and {@code ..}
 * resolved, its links resolved ({@link Path#toRealPath}: symbolic links, junctions, 8.3 names; for a
 * file that does not exist yet, its nearest existing folder resolved plus the rest), and lower-cased
 * where file names ignore case (Windows, macOS). Two names are one file when their canonical forms are equal
 * ({@link #same}); a path is inside a folder when its form is the folder's or starts with it and a
 * {@code /} ({@link #inside}). Example on Windows: {@code C:\link\App\a.txt} and
 * {@code c:/ws/app/A.TXT}, with {@code C:\link} a junction to {@code C:\ws}, are both
 * {@code c:/ws/app/a.txt}.
 *
 * <p>Pure: the platform is a parameter ({@link Platform}), so the Windows and macOS rules are tested
 * on any machine, with a fake {@code realPath}. The methods without one use this machine's
 * ({@link Platform#current()}).
 */
public final class FilePaths {

    private FilePaths() {
    }

    /**
     * Which rule applies.
     *
     * @param windows    {@code \} is a separator too, and {@code C:/} or {@code //server} starts an
     *                   absolute path
     * @param ignoreCase file names ignore case (Windows, macOS)
     * @param realPath   the path with its links resolved, or {@code null} when it does not exist; it
     *                   is given and may answer with either separator
     */
    public record Platform(boolean windows, boolean ignoreCase, UnaryOperator<String> realPath) {

        /**
         * This machine's rule: case ignored on Windows and macOS (as Neovim's {@code 'fileignorecase'}
         * and Eclipse's local file system have it), links resolved with {@link Path#toRealPath}.
         */
        public static Platform current() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            boolean windows = os.startsWith("windows");
            boolean mac = os.startsWith("mac");
            return new Platform(windows, windows || mac, FilePaths::realPath);
        }
    }

    private static String realPath(String path) {
        try {
            return Path.of(path).toRealPath().toString();
        } catch (IOException | InvalidPathException | SecurityException e) {
            return null; // not there (yet), or not a path on this system
        }
    }

    /** {@code path} in the rule's one form; "" for {@code null} or "". See the class comment. */
    public static String canonical(String path, Platform platform) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String real = linksResolved(normalize(absolute(path, platform), platform.windows()), platform);
        return platform.ignoreCase() ? real.toLowerCase(Locale.ROOT) : real;
    }

    /**
     * Normalized absolute {@code full} with its links resolved: its real path, or for a path that does
     * not exist (yet) its nearest existing folder's real path plus the rest of it, normalized. The
     * nearest, not just the parent: on macOS {@code /home} is a link to
     * {@code /System/Volumes/Data/home}, and with only the parent tried {@code /home/user} (parent
     * {@code /home} exists) and {@code /home/user/notes.txt} (parent {@code /home/user} does not)
     * would resolve differently, so the file would not be inside its own folder (found 2026-10-29,
     * {@code EditPathTest} on macOS). {@code full} itself when nothing resolves.
     */
    private static String linksResolved(String full, Platform platform) {
        int root = rootLength(full, platform.windows());
        String folder = full;
        String rest = "";
        while (true) {
            String real = platform.realPath().apply(folder);
            if (real != null) {
                return normalize(rest.isEmpty() ? real : real + "/" + rest, platform.windows());
            }
            int slash = folder.lastIndexOf('/');
            if (folder.length() <= root || slash < 0) {
                return full; // not even the root resolves (a fake platform, or a drive not there)
            }
            String name = folder.substring(slash + 1);
            rest = rest.isEmpty() ? name : name + "/" + rest;
            folder = slash < root ? folder.substring(0, root) : folder.substring(0, slash);
        }
    }

    /** Whether {@code a} and {@code b} name one file under {@code platform}'s rule. */
    public static boolean same(String a, String b, Platform platform) {
        return canonical(a, platform).equals(canonical(b, platform));
    }

    /** Whether {@code path} is the folder {@code folder} or inside it, under {@code platform}'s rule. */
    public static boolean inside(String path, String folder, Platform platform) {
        return within(canonical(path, platform), canonical(folder, platform));
    }

    /** {@link #same} under this machine's rule. */
    public static boolean same(Path a, Path b) {
        return same(a.toString(), b.toString(), Platform.current());
    }

    /**
     * {@code path} with its links resolved as the rule resolves them, in the system's own form (its
     * case kept), for a lookup that needs a real path rather than a comparison: {@link Path#toRealPath},
     * or for a file not there yet its nearest existing folder's real path plus the rest. Example on
     * macOS: {@code /var/folders/newdir/new.txt} gives {@code /private/var/folders/newdir/new.txt}.
     */
    public static Path resolved(Path path) {
        Platform here = Platform.current();
        String full = normalize(absolute(path.toString(), here), here.windows());
        return Path.of(linksResolved(full, here));
    }

    /**
     * The part of {@code path} below {@code folder} as it is written in {@code path} (its own case),
     * with {@code /}, when it is inside the folder under {@code platform}'s rule and not the folder
     * itself; else {@code null}. For a name shown shortened: window folder {@code /ws/App} and file
     * {@code /ws/app/src/A.java} give {@code src/A.java} on macOS.
     */
    public static String below(String path, String folder, Platform platform) {
        String c = canonical(path, platform);
        String f = canonical(folder, platform);
        if (c.equals(f) || !within(c, f)) {
            return null;
        }
        int depth = segments(c).size() - segments(f).size();
        List<String> written = segments(normalize(absolute(path, platform), platform.windows()));
        return depth <= 0 || depth > written.size() ? null
                : String.join("/", written.subList(written.size() - depth, written.size()));
    }

    /** Whether canonical form {@code c} is the canonical folder {@code f} or inside it. */
    static boolean within(String c, String f) {
        String prefix = f.endsWith("/") ? f : f + "/"; // the root: / or C:/
        return c.equals(f) || c.startsWith(prefix);
    }

    private static String absolute(String path, Platform platform) {
        if (isAbsolute(path, platform.windows())) {
            return path;
        }
        try {
            return Path.of(path).toAbsolutePath().toString();
        } catch (InvalidPathException e) {
            return path;
        }
    }

    private static boolean isAbsolute(String path, boolean windows) {
        if (windows) {
            return path.matches("^[A-Za-z]:[/\\\\].*") || path.matches("^[/\\\\][/\\\\].*");
        }
        return path.startsWith("/");
    }

    /** How many characters of an absolute normalized path are its root: {@code /}, {@code C:/}, {@code //}. */
    private static int rootLength(String path, boolean windows) {
        if (windows && path.length() >= 3 && path.charAt(1) == ':') {
            return 3;
        }
        if (windows && path.startsWith("//")) {
            return 2;
        }
        return 1;
    }

    /**
     * {@code /} separators ({@code \} too on Windows), {@code .}, {@code ..} and doubled slashes
     * resolved, no trailing slash but at the root, the drive letter upper-cased: as Neovim's
     * {@code vim.fs.normalize} does, so the two rules give one form.
     */
    static String normalize(String path, boolean windows) {
        String p = windows ? path.replace('\\', '/') : path;
        String root;
        if (windows && p.length() >= 2 && p.charAt(1) == ':') {
            root = Character.toUpperCase(p.charAt(0)) + ":/";
            p = p.substring(2);
        } else if (windows && p.startsWith("//")) {
            root = "//";
        } else if (p.startsWith("/")) {
            root = "/";
        } else {
            root = "";
        }
        List<String> parts = new ArrayList<>();
        for (String part : p.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..") && !parts.isEmpty() && !parts.get(parts.size() - 1).equals("..")) {
                parts.remove(parts.size() - 1);
            } else if (!(part.equals("..") && !root.isEmpty())) {
                parts.add(part);
            }
        }
        return root + String.join("/", parts);
    }

    private static List<String> segments(String path) {
        List<String> list = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isEmpty()) {
                list.add(part);
            }
        }
        return list;
    }
}
