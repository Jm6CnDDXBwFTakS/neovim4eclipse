// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.util.Util;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.VimCtrlKeys;

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
     * Logs which command the workbench would run for {@code sequence} right now (with the current
     * active contexts): INFO if it is our command, WARNING if some other command wins or none is
     * bound. Must run on the UI thread, after the Neovim context was activated.
     *
     * @param sequence unused placeholder kept for call-site readability; the platform's Vim-Ctrl-V
     *                 sequence is checked ({@code M1+V}, or {@code M4+V} on macOS)
     */
    static void logResolution(String sequence) {
        String text = Util.isMac() ? "M4+V" : "M1+V";
        try {
            String commandId = KeyBindings.commandIdFor(text);
            if (VimCtrlKeys.isCtrlKeyCommand(commandId)) {
                LOG.fine("Ctrl-V (" + text + ") resolves to the Neovim Ctrl-key command "
                        + "(context is active and the binding wins)");
            } else {
                LOG.warning("Ctrl-V (" + text + ") resolves to '" + commandId + "', not to a Neovim Ctrl-key command: "
                        + "either the Neovim key context is not active in the workbench, or another binding "
                        + "outranks it. Vim Ctrl keys will not work until this is resolved.");
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Key binding check for " + text + " failed", e);
        }
    }
}
