// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Command "Neovim: Attach to Active Editor" (Ctrl+Shift+N, Cmd+Shift+N on macOS, or Quick Access). Auto-attach normally
 * makes it unnecessary, so it has no menu entry.
 *
 * <p>A <em>handler</em> is the code behind a command. {@code plugin.xml} declares the command
 * ({@code org.eclipse.ui.commands}), binds a key to it ({@code org.eclipse.ui.bindings}) and names this class as its handler
 * ({@code org.eclipse.ui.handlers}). Eclipse creates the handler on first use and calls
 * {@link #execute} on the UI thread. {@link AbstractHandler} supplies the rest of the
 * {@code IHandler} interface (enablement, listeners).
 */
public class AttachHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        IEditorPart editor = HandlerUtil.getActiveEditor(event);
        if (editor == null) {
            MessageDialog.openInformation(window.getShell(), "Neovim",
                    "No active editor. Open a text file first.");
            return null;
        }
        try {
            EditorAttachManager.getInstance().attach(editor, true);
            MessageDialog.openInformation(window.getShell(), "Neovim",
                    "Attached Neovim to: " + editor.getTitle() + "\n\n"
                            + "Keys are handled by Neovim (motions, text objects, insert).\n"
                            + "Examples: j/k, w/e/b, diw, ci\", dd, i…Esc");
        } catch (Exception e) {
            throw new ExecutionException("Failed to attach Neovim", e);
        }
        return null;
    }
}
