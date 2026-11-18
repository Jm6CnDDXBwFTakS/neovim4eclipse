// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.List;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CmdlineState;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CommandWindowView;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CompletionMenu;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FloatWindow;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HostCommand;

/**
 * The part that has the focus, as Neovim sees it: what follows Neovim's <em>current window</em>.
 * {@link FocusRouter} hands each of these events to the one target that has the focus now.
 *
 * <h2>Two groups of events</h2>
 * A Neovim session reports two kinds of things, and they follow different parts:
 * <ul>
 *   <li><b>Focus events</b> (this interface) belong to whatever window is current, as in Neovim:
 *       the mode, a register being recorded, the status line and messages, command output, the
 *       {@code :} line, the command-line window ({@code q:}), floating windows, the completion
 *       list, and the commands and IDE actions a key asks for. In a terminal Neovim they show at
 *       the bottom of the screen, or at the current window's cursor, whichever window that is.</li>
 *   <li><b>Document events</b> belong to the text editor whose document Neovim mirrors (the
 *       <em>attached</em> one, {@code DocumentBuffers}): its cursor, visual selection, folds, wrap,
 *       top line, search matches and highlights. They are filtered by window and buffer, and go to
 *       that editor's listeners directly ({@code NeovimSession.addCursorListener} and the rest),
 *       not through here.</li>
 * </ul>
 * Today the text editor's controller is both: the attached editor and the focus target. A page
 * editor shown from a window of the editing Neovim would be a focus target only, while the text
 * editor behind it stays attached, its document and caret untouched.
 *
 * <p>Every method is called on a session thread (the input thread or an RPC thread) and must hop
 * to the UI thread itself. Each has an empty default, so that a target takes only what it shows.
 */
public interface FocusTarget {

    /** Neovim's mode changed ({@code nvim_get_mode}'s name: {@code n}, {@code i}, {@code v}, ...). */
    default void onMode(String mode) {
    }

    /** A register started recording ({@code q}), or {@code ""} when it stopped. */
    default void onRecording(String register) {
    }

    /** The status bar's text changed: the pending command, a message, the status line. */
    default void onStatus(StatusInfo status) {
    }

    /** Output for the popup: a listing, shell output, a message of several lines. */
    default void onOutput(CommandOutput output) {
    }

    /** A key mapped in Neovim asks the IDE to act ({@code gd}, {@code gr*}). */
    default void onIdeAction(IdeAction action) {
    }

    /** Neovim hands a command to Eclipse ({@code :q}, {@code :split}, {@code :tabnext}). */
    default void onHostCommand(HostCommand command) {
    }

    /** The {@code :}, {@code /} or {@code ?} line opened, changed or closed. */
    default void onCmdline(CmdlineState.View view) {
    }

    /** The command-line window ({@code q:}) opened, changed or closed. */
    default void onCommandWindow(CommandWindowView view) {
    }

    /** The floating windows to draw changed; an empty list when the last one closed. */
    default void onFloatingWindows(List<FloatWindow> windows) {
    }

    /** The insert-mode completion list showed, changed or closed. */
    default void onCompletionMenu(CompletionMenu.View view) {
    }

    /**
     * The page window this target shows, or {@link HostCommand#NO_WINDOW} for a text editor: a host
     * command is judged against the target that gets it ({@code util/CommandOrigin}), read once with
     * it, so the decision and the delivery cannot see two different targets. Any thread.
     */
    default int pageWindow() {
        return HostCommand.NO_WINDOW;
    }
}
