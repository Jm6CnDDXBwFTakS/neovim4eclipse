// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Toggles between Neovim key handling and plain Eclipse editing on the active editor (the NVIM
 * toolbar button and its key binding). Neovim stays attached in Eclipse mode, so switching back
 * is instant. See {@link AttachHandler} for how handlers work.
 */
public class ToggleNeovimModeHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        if (controller == null || controller.isClosed()) {
            MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "Neovim",
                    "No editor is attached. Open a text file (auto-attach) or use Neovim → Attach.");
            return null;
        }
        // No confirmation dialog: the status line shows the resulting mode.
        controller.toggleNeovimMode();
        return null;
    }
}
