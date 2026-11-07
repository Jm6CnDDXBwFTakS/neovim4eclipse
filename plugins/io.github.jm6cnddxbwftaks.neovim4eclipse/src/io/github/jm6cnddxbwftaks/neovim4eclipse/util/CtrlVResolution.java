// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * What {@code editor/KeyContextDiagnostics} makes of the command the workbench resolves for
 * Ctrl-V just after the Neovim key context turned on. The check runs a moment later (posted to
 * the UI thread), so it must not take two harmless states for a broken binding:
 * <ul>
 *   <li>the context is <b>no longer active</b>: the editor lost focus or closed before the check
 *       ran, so Eclipse's paste is what should win now;</li>
 *   <li>Ctrl-V <b>goes to Eclipse by choice</b> in the current mode: on Linux and Windows it stays
 *       Eclipse's paste in insert mode by default ({@link VimCtrlKeys#INSERT_BY_DEFAULT} has no
 *       {@code v}), and a user can give it to Eclipse in any mode on the Ctrl Keys page.</li>
 * </ul>
 * Example: the editor takes focus while Neovim is in insert mode, on Linux. The workbench answers
 * {@code org.eclipse.ui.edit.paste}, which is right, so this says {@link #EXPECTED_ECLIPSE}, not
 * {@link #WRONG}.
 */
public enum CtrlVResolution {

    /** The context is off by now: nothing to report. */
    NOT_ACTIVE,
    /** Ctrl-V reaches the plug-in's command, as it should. */
    NEOVIM,
    /** Ctrl-V reaches an Eclipse command, as the user's (or the default) choice says it should. */
    EXPECTED_ECLIPSE,
    /** Ctrl-V should reach Neovim but does not: another binding wins, or the context did not take. */
    WRONG;

    /**
     * @param contextActive  whether the Neovim key context is still active when the check runs
     * @param shouldReachNeovim whether Ctrl-V goes to Neovim in the current mode (the preferences)
     * @param reachesNeovim  whether the workbench resolves Ctrl-V to one of the plug-in's commands
     */
    public static CtrlVResolution of(boolean contextActive, boolean shouldReachNeovim, boolean reachesNeovim) {
        if (!contextActive) {
            return NOT_ACTIVE;
        }
        if (reachesNeovim) {
            return NEOVIM;
        }
        return shouldReachNeovim ? WRONG : EXPECTED_ECLIPSE;
    }
}
