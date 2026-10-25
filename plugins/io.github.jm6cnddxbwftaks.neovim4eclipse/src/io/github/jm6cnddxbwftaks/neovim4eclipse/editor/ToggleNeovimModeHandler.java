// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Toggles between Neovim key handling and plain Eclipse editing on the active editor (the Neovim
 * toolbar button and its key binding). Neovim stays attached in Eclipse mode, so switching back
 * is instant. See {@link AttachHandler} for how handlers work.
 */
public class ToggleNeovimModeHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        if (controller == null || controller.isClosed()) {
            MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "Neovim",
                    "Neovim is not attached to this editor. It attaches to text editors by itself; if it"
                            + " did not, it may have failed to start (Window > Preferences > Neovim names the"
                            + " executable). To try again: Neovim: Attach to Active Editor (Ctrl+Shift+N), or"
                            + " Quick Access (Ctrl+3).");
            return null;
        }
        // No confirmation dialog: the status line shows the resulting mode.
        controller.toggleNeovimMode();
        return null;
    }
}
