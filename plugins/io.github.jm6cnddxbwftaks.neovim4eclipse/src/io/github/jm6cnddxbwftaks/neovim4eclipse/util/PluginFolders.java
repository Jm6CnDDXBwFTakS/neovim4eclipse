// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Where the plug-in keeps files of its own: in the data home Neovim uses (Neovim's
 * <a href="https://neovim.io/doc/user/starting.html#base-directories">{@code :h base-directories}</a>),
 * which follows the <a href="https://specifications.freedesktop.org/basedir-spec/latest/">XDG Base
 * Directory specification</a> on Linux and macOS, and Windows' own folder for local application data
 * on Windows.
 *
 * <p>The data home is, in order (a variable counts only if set to an absolute path; the XDG
 * specification says to ignore a relative one):
 * <ul>
 *   <li>Linux and macOS: {@code $XDG_DATA_HOME}, then {@code $HOME/.local/share}, then the Java home
 *       folder ({@code user.home}) plus {@code .local/share}.</li>
 *   <li>Windows: {@code %XDG_DATA_HOME%}, then {@code %LOCALAPPDATA%}, then {@code %HOME%\AppData\Local},
 *       then {@code user.home} ({@code %USERPROFILE%}) plus {@code AppData\Local}.</li>
 * </ul>
 * The plug-in's folder in it is {@value #NAME}: not {@code neovim4eclipse}, which someone may well
 * choose as {@code NVIM_APPNAME} for a Neovim config of Eclipse's own, and Neovim would then keep its
 * data in that very folder.
 *
 * <p>Why the same data home as Neovim: a Neovim with {@code NVIM_APPNAME=neovim4eclipse-plugin/tests}
 * (the UI tests' Neovim) then keeps its data inside the plug-in's folder, not in a second folder
 * elsewhere; and on Windows the folder is where Windows programs keep local data, not in a Unix-style
 * {@code .local\share} in the home folder. Earlier builds used the Unix rule on Windows too (so
 * {@code C:\Users\you\.local\share\neovim4eclipse-plugin}); that folder is no longer looked at.
 *
 * <p>The Windows order is what Neovim 0.12.6 does, checked on 2026-11-05 with
 * {@code nvim --clean -i NONE --headless "+lua io.write(vim.fn.stdpath('data'))" +qa} (Neovim adds
 * {@code -data} to the app name, {@code nvim-data}):
 * <pre>
 *   XDG_DATA_HOME=C:\xdg-test                         C:\xdg-test\nvim-data
 *   LOCALAPPDATA=C:\Users\you\AppData\Local, any HOME  C:\Users\you\AppData\Local\nvim-data
 *   LOCALAPPDATA=C:\fake-local                         C:\fake-local\nvim-data
 *   no LOCALAPPDATA, HOME=C:\fake-home                 C:\fake-home\AppData\Local\nvim-data
 *   no LOCALAPPDATA, no HOME                           C:\Users\you\AppData\Local\nvim-data
 *   NVIM_APPNAME=neovim4eclipse-plugin/tests           C:\Users\you\AppData\Local\neovim4eclipse-plugin\tests-data
 * </pre>
 * So {@code HOME} matters only when {@code LOCALAPPDATA} is missing.
 *
 * <p>Eclipse sees only the environment of whatever started it. A variable set in a shell's startup
 * file is missing when Eclipse starts from the macOS Dock or the Windows Start menu, and the folder
 * then falls back to the next rule. The preference page shows the folder in use.
 *
 * <p>Example: with nothing set, on Linux {@code /home/you/.local/share/neovim4eclipse-plugin}, on
 * Windows {@code C:\Users\you\AppData\Local\neovim4eclipse-plugin}.
 */
public final class PluginFolders {

    /** The plug-in's folder name inside the data home. */
    public static final String NAME = "neovim4eclipse-plugin";

    private PluginFolders() {
    }

    /** The data home for this process's environment; see the class comment for the rules. */
    public static Path dataHome() {
        return dataHome(System.getenv(), System.getProperty("user.home", ""), PlatformUtil.isWindows());
    }

    /**
     * Testable core of {@link #dataHome()}.
     *
     * @param env      the environment variables
     * @param userHome Java's {@code user.home}
     * @param windows  whether to apply the Windows rule
     */
    static Path dataHome(Map<String, String> env, String userHome, boolean windows) {
        Path xdg = absolute(env.get("XDG_DATA_HOME"));
        if (xdg != null) {
            return xdg;
        }
        if (windows) {
            Path localAppData = absolute(env.get("LOCALAPPDATA"));
            if (localAppData != null) {
                return localAppData;
            }
        }
        Path home = absolute(env.get("HOME"));
        Path base = home != null ? home : Path.of(userHome);
        return windows ? base.resolve("AppData").resolve("Local") : base.resolve(".local").resolve("share");
    }

    /** The plug-in's own folder, for anything it keeps: {@code <data home>/neovim4eclipse-plugin}. */
    public static Path pluginFolder() {
        return dataHome().resolve(NAME);
    }

    /**
     * The folder meant for a Neovim used by this plug-in alone: {@code <plug-in folder>/neovim}. Extract
     * a release archive there (its top folder may stay) and leave the preference blank.
     */
    public static Path neovimFolder() {
        return pluginFolder().resolve("neovim");
    }

    /** {@code value} as an absolute path, or {@code null} if it is unset, blank, relative or invalid. */
    private static Path absolute(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(value.strip());
            return path.isAbsolute() ? path : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
