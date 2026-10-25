// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Where the plug-in keeps files of its own, following the
 * <a href="https://specifications.freedesktop.org/basedir-spec/latest/">XDG Base Directory
 * specification</a> on every platform, as Neovim does.
 *
 * <p>The data home is, in order:
 * <ol>
 *   <li>{@code $XDG_DATA_HOME}, if set to an absolute path (the specification says to ignore a
 *       relative one);</li>
 *   <li>{@code $HOME/.local/share}, if {@code HOME} is set to an absolute path (on Windows too: Git
 *       for Windows sets it);</li>
 *   <li>the Java home folder ({@code user.home}; {@code %USERPROFILE%} on Windows) plus
 *       {@code .local/share}.</li>
 * </ol>
 * The plug-in's folder in it is {@value #NAME}: not {@code neovim4eclipse}, which someone may well
 * choose as {@code NVIM_APPNAME} for a Neovim config of Eclipse's own, and Neovim would then keep its
 * data in that very folder.
 *
 * <p>Eclipse sees only the environment of whatever started it. A variable set in a shell's startup
 * file is missing when Eclipse starts from the macOS Dock or the Windows Start menu, and the folder
 * then falls back to the next rule. The preference page shows the folder in use.
 *
 * <p>Example: with nothing set, on Linux {@code /home/you/.local/share/neovim4eclipse-plugin}, on
 * Windows {@code C:\Users\you\.local\share\neovim4eclipse-plugin}.
 */
public final class PluginFolders {

    /** The plug-in's folder name inside the data home. */
    public static final String NAME = "neovim4eclipse-plugin";

    private PluginFolders() {
    }

    /** The data home for this process's environment; see the class comment for the rules. */
    public static Path dataHome() {
        return dataHome(System.getenv(), System.getProperty("user.home", ""));
    }

    /**
     * Testable core of {@link #dataHome()}.
     *
     * @param env      the environment variables
     * @param userHome Java's {@code user.home}
     */
    static Path dataHome(Map<String, String> env, String userHome) {
        Path xdg = absolute(env.get("XDG_DATA_HOME"));
        if (xdg != null) {
            return xdg;
        }
        Path home = absolute(env.get("HOME"));
        return (home != null ? home : Path.of(userHome)).resolve(".local").resolve("share");
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
