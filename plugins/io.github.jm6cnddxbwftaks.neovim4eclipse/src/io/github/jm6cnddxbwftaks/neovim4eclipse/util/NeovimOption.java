// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.List;

/**
 * The one way to run a Neovim other than the dedicated one: the Java system property
 * {@value #PROPERTY}, given when Eclipse starts. Example, on the command line:
 *
 * <pre>
 *   eclipse -vmargs -Dneovim4eclipse.nvim=/usr/local/bin/nvim
 * </pre>
 *
 * or as a line after {@code -vmargs} in {@code eclipse.ini}. Unset or blank, the plug-in runs the
 * dedicated Neovim that Get Neovim installs and checks ({@link NvimExecutable#find}).
 *
 * <h2>Why a startup option, not a preference</h2>
 * Until 0.2.1 the program was the "Neovim program" preference. A preference is stored in the
 * workspace and can arrive from elsewhere: <i>File &gt; Import &gt; Preferences</i> reads a
 * {@code .epf} file that teams pass around, and such a file could name any program, which the
 * plug-in then ran unchecked. A startup option is set only by whoever starts Eclipse or edits its
 * installation. It is also read once, before the plug-in starts, so the UI tests' Eclipse gets the
 * test Neovim through Tycho's system properties without a window in which the preference is still
 * blank (Get Neovim then opened by itself at the test Eclipse's start).
 *
 * <h2>The command line adds to eclipse.ini</h2>
 * A {@code -vmargs} on the command line replaces {@code eclipse.ini}'s unless the latter has
 * {@code --launcher.appendVmargs}; the Eclipse packages (EPP) have it, checked on Linux, macOS and
 * Windows on 2026-11-05 (and docs/design/LOG_LEVEL.md, section 2). Without it, a command line that
 * replaces them drops {@code -XstartOnFirstThread} on macOS, and Eclipse does not start: then the
 * line belongs in {@code eclipse.ini}.
 *
 * <p>Pure, so the rules are unit-tested: {@link #of} takes the property's value.
 */
public final class NeovimOption {

    /** The system property's name. */
    public static final String PROPERTY = "neovim4eclipse.nvim";

    private NeovimOption() {
    }

    /** The program this Eclipse was started with, stripped; {@code ""} when unset (the dedicated Neovim). */
    public static String value() {
        return of(System.getProperty(PROPERTY));
    }

    /** Testable core of {@link #value}: {@code null} and blank both mean unset. */
    static String of(String property) {
        return property == null ? "" : property.strip();
    }

    /**
     * The option as it is written in {@code eclipse.ini}, a line after {@code -vmargs}. Example:
     * {@code -Dneovim4eclipse.nvim=/usr/local/bin/nvim}.
     */
    public static String iniLine(String program) {
        return "-D" + PROPERTY + "=" + program;
    }

    /**
     * The option as it is written after the Eclipse program on the command line: {@code -vmargs -D...}.
     * A path with a space is quoted, so a shell or {@code cmd.exe} passes it as one argument:
     * {@code -vmargs "-Dneovim4eclipse.nvim=C:\Program Files\Neovim\bin\nvim.exe"}. Double quotes
     * work in both. The {@code eclipse.ini} line ({@link #iniLine}) is never quoted: each line there
     * is one argument as it stands, and quotes would become part of the path.
     */
    public static String commandLine(String program) {
        String option = iniLine(program);
        return "-vmargs " + (option.chars().anyMatch(Character::isWhitespace) ? "\"" + option + "\"" : option);
    }

    /**
     * The rows of a dialog that says how to name {@code program}: the command line and the
     * {@code eclipse.ini} line, each in a Copy field of its own, so nothing copied needs editing (the
     * two differ: the command line has {@code -vmargs} and, for a path with a space, quotes; the
     * line in {@code eclipse.ini} has neither). Then {@code end}, if not {@code null}. Example:
     *
     * <pre>
     *   Instead  To use a Neovim of your own, cancel and start Eclipse with:
     *            [-vmargs -Dneovim4eclipse.nvim=/path/to/nvim]   (Copy)
     *            or add this line after -vmargs in eclipse.ini:
     *            [-Dneovim4eclipse.nvim=/path/to/nvim]           (Copy)
     *            It is run unchecked, so be sure it is Neovim.
     * </pre>
     *
     * @param heading the first row's heading ({@code Instead})
     * @param lead    the sentence before the command line, ending in a colon
     */
    public static List<HeadedMessage.Point> rows(String heading, String lead, String program, String end) {
        List<HeadedMessage.Point> rows = new ArrayList<>(List.of(
                new HeadedMessage.Point(heading, lead).withCopy(commandLine(program)),
                new HeadedMessage.Point("", "or add this line after -vmargs in eclipse.ini:").withCopy(iniLine(program))));
        if (end != null) {
            rows.add(new HeadedMessage.Point("", end));
        }
        return List.copyOf(rows);
    }
}
