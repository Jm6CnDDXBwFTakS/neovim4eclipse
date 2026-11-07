// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.util.Util;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CtrlVResolution;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimCtrlKeys;

/**
 * Explains, in the Eclipse Error Log, where a Vim Ctrl key ends up. Eclipse key bindings are
 * resolved by the workbench before the editor sees a key, and when one of ours does not win the
 * symptom is simply "nothing happens", so this reports what the workbench would actually run.
 */
final class KeyContextDiagnostics {

    private static final Logger LOG = Logger.getLogger(KeyContextDiagnostics.class.getName());

    private KeyContextDiagnostics() {
    }

    /**
     * Logs which command the workbench would run for the platform's Vim Ctrl-V ({@code M1+V}, or
     * {@code M4+V} on macOS) right now: FINE when that is what it should run, WARNING only when
     * Ctrl-V should reach Neovim and does not. {@link CtrlVResolution} decides; it keeps quiet about
     * a context that was turned off again before this ran, and about Ctrl-V staying Eclipse's paste
     * where the preferences give it to Eclipse (insert mode on Linux and Windows, by default). UI
     * thread.
     *
     * @param contextActive     whether the Neovim key context is still active
     * @param shouldReachNeovim whether Ctrl-V goes to Neovim in Neovim's current mode
     */
    static void logResolution(boolean contextActive, boolean shouldReachNeovim) {
        String text = Util.isMac() ? "M4+V" : "M1+V";
        try {
            String commandId = KeyBindings.commandIdFor(text);
            // On macOS Ctrl-V (M4+V) has no Eclipse binding at all (paste is Cmd-V), so "no command" is the usual answer there.
            String resolved = commandId == null ? "no command" : "'" + commandId + "'";
            switch (CtrlVResolution.of(contextActive, shouldReachNeovim, VimCtrlKeys.isCtrlKeyCommand(commandId))) {
                case NOT_ACTIVE -> {
                    // Focus moved on before this ran: Eclipse's own binding is right again.
                }
                case NEOVIM -> LOG.fine("Ctrl-V (" + text + ") resolves to the Neovim Ctrl-key command "
                        + "(context is active and the binding wins)");
                case EXPECTED_ECLIPSE -> LOG.fine("Ctrl-V (" + text + ") resolves to " + resolved
                        + ", as chosen: Ctrl-V goes to Eclipse in this mode (Ctrl Keys preferences)");
                case WRONG -> LOG.warning("Ctrl-V (" + text + ") resolves to " + resolved
                        + ", not to a Neovim Ctrl-key command: either the Neovim key context is not active in the "
                        + "workbench, or another binding outranks it. Vim Ctrl keys will not work until this is resolved.");
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Key binding check for " + text + " failed", e);
        }
    }
}
