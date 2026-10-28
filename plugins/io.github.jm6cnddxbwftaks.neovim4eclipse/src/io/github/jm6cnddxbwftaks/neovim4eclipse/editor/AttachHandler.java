// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.AttachRefusal;

/**
 * Command "Neovim: Attach Neovim" (<em>Neovim → Attach Neovim</em>, or Quick Access;
 * no key since 0.1.9, a user can bind one). Auto-attach normally makes it unnecessary; it is for an editor that did
 * not attach by itself. It also starts a new Neovim if the old one died
 * ({@code Activator.getOrCreateSession}).
 *
 * <p>Quiet when it works: the editor's status line shows the Vim mode, which says enough, and a
 * dialog on every attach would be noise from a menu. An editor already attached is left as it is.
 * Only a refusal (no text editor) gets a dialog; a failure to start Neovim gets the start-failure
 * notice ({@code StartFailureNotice}) and the error below.
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
        String refusal = AttachRefusal.reason(editor == null ? null : editor.getTitle(),
                editor != null && EditorAttachManager.textEditorOf(editor) != null);
        if (refusal != null) {
            // A message, not an exception: Eclipse would only write the exception to its Error Log,
            // and the key would seem to do nothing (an image, a manifest's Overview page).
            MessageDialog.openInformation(window.getShell(), "Neovim", refusal);
            return null;
        }
        try {
            EditorAttachManager.getInstance().attach(editor, true);
        } catch (Exception e) {
            throw new ExecutionException("Failed to attach Neovim", e);
        }
        return null;
    }
}
