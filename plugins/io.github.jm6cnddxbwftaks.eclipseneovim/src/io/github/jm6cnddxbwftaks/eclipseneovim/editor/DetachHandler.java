// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Command "Neovim: Detach from Active Editor" (Quick Access). Stops Neovim handling keys in the active editor until the next
 * attach (another editor activated, or Attach). See {@link AttachHandler} for how handlers work.
 */
public class DetachHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (EditorAttachManager.getInstance().getActiveController() == null) {
            MessageDialog.openInformation(window.getShell(), "Neovim", "Nothing attached.");
            return null;
        }
        EditorAttachManager.getInstance().detach();
        MessageDialog.openInformation(window.getShell(), "Neovim", "Detached.");
        return null;
    }
}
