// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * What {@code :terminal} does in Eclipse: it opens Eclipse's own Terminal view with a shell, instead
 * of a Neovim terminal, which Eclipse has no view for (its program would run out of sight).
 *
 * <h2>Opened by command id, without depending on the Terminal</h2>
 * Eclipse's Terminal is an optional part of an Eclipse install, and it was renamed inside the range
 * of Eclipse releases the plug-in supports: up to 2025-06 its bundles are
 * {@code org.eclipse.tm.terminal.*} (the TM project), from 2025-12 {@code org.eclipse.terminal.*}
 * (moved into the Platform), and 2025-09 has both. Compiling against either Java API would tie the
 * plug-in to one generation. Both define the same command, <i>Open local terminal</i>, under their
 * own prefix; an Eclipse command can be run by its id alone ({@code IHandlerService.executeCommand}),
 * so the plug-in needs neither bundle. Its handler opens a shell in the folder of the active
 * editor's file (the same code in both generations), which is the editor {@code :terminal} was
 * typed in. An Eclipse without the Terminal has no such command defined: {@link #commandId} is
 * empty, and {@link #NO_TERMINAL} says how to get one.
 *
 * <h2>What the command cannot do</h2>
 * It opens a shell, nothing else: {@code :terminal make} opens the shell but does not run
 * {@code make} (running a given program needs the Terminal's Java API), so {@link #notRun} says so,
 * and the user types it there. The command is never started in Neovim either: a typed
 * {@code :terminal} is caught on the {@code :} line before Neovim runs it
 * ({@link HostCommand.Kind#TERMINAL}). Nothing runs part way, and nothing goes unrun without a
 * message.
 *
 * <p>Pure Java, no Eclipse: the editor's {@code HostCommands} asks which commands are defined and
 * runs the one chosen here. Tested in {@code EclipseTerminalTest}.
 */
public final class EclipseTerminal {

    /** <i>Open local terminal</i> in Eclipse 2025-09 and later ({@code org.eclipse.terminal.connector.local}, in the Platform). */
    public static final String COMMAND = "org.eclipse.terminal.connector.local.command.launch";

    /** The same command in Eclipse 2024-12 to 2025-09 ({@code org.eclipse.tm.terminal.connector.local}, the TM Terminal). */
    public static final String OLD_COMMAND = "org.eclipse.tm.terminal.connector.local.command.launch";

    /** The ids to try, newest first: 2025-09 has both, and the new one is the one kept. */
    public static final List<String> COMMANDS = List.of(COMMAND, OLD_COMMAND);

    /** The message when this Eclipse has no Terminal (no command id is defined). */
    public static final String NO_TERMINAL =
            "terminal: this Eclipse has no Terminal view (Help > Install New Software, Eclipse's Terminal)";

    private EclipseTerminal() {
    }

    /**
     * The command to run: the first of {@link #COMMANDS} that this Eclipse defines, or empty when it
     * defines none (no Terminal installed).
     *
     * @param defined whether Eclipse defines a command id ({@code ICommandService.getCommand(id).isDefined()})
     */
    public static Optional<String> commandId(Predicate<String> defined) {
        return COMMANDS.stream().filter(defined).findFirst();
    }

    /**
     * The command typed after {@code :terminal}, for the message, word for word as Neovim split it:
     * {@code [make, -j4]} gives {@code make -j4}; "" when only a shell was asked for. Nothing is
     * left out: Neovim's {@code :terminal} takes no options of its own (Vim's {@code ++close} would
     * be part of Neovim's shell line), so every word is part of what was not run.
     */
    public static String command(List<String> args) {
        return String.join(" ", args);
    }

    /**
     * The message after the Terminal opened for {@code :terminal make}, or {@code null} when no command
     * was typed (a shell was asked for, and a shell opened: nothing to say). Example:
     * {@code terminal: opened Eclipse's Terminal in ~/src/app; "make" was not run, type it there}.
     *
     * @param command what {@link #command} gives
     * @param folder  the folder the shell starts in (the file's), or {@code null} when the editor
     *                has no file (an untitled one): then the Terminal picks its own (by its
     *                preferences, usually the home folder) and the message names none
     * @param home    the user's home folder, shown as {@code ~}
     */
    public static String notRun(String command, Path folder, Path home) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String where = folder == null ? "" : " in " + EditPath.shown(folder, home, null);
        return "terminal: opened Eclipse's Terminal" + where + "; \"" + command + "\" was not run, type it there";
    }
}
