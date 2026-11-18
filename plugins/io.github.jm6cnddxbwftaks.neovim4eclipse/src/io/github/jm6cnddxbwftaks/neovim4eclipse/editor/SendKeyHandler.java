// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.logging.Logger;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.handlers.HandlerUtil;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimCtrlKeys;

/**
 * Sends one Vim {@code Ctrl} key (for example {@code <C-v>}) to the attached Neovim, or, for
 * {@code <C-w>} typed in the Neovim view or a page editor, to its Neovim ({@link NeovimView#KEY_CONTEXT_ID}).
 *
 * <p>Bound in plugin.xml, in the {@link EditorKeyContext#KEY_CONTEXT_ID} context, to the
 * {@code Ctrl} keys that Eclipse would otherwise handle itself before the editor sees them (the
 * workbench dispatches key bindings ahead of the widget's key listeners). There is one command per
 * key, and the key is derived from the command id ({@link VimCtrlKeys#keyFor}); command parameters
 * are deliberately not used because they were not delivered reliably on every platform.
 */
public final class SendKeyHandler extends AbstractHandler {

    private static final Logger LOG = Logger.getLogger(SendKeyHandler.class.getName());

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        String commandId = event.getCommand() == null ? null : event.getCommand().getId();
        String key = VimCtrlKeys.keyFor(commandId);
        // The Neovim view binds Ctrl+W too (NeovimView.KEY_CONTEXT_ID): there the key is its Neovim's.
        if (key != null && HandlerUtil.getActivePart(event) instanceof NeovimView view) {
            LOG.fine(() -> "Ctrl-key command " + commandId + " -> " + key + " in the Neovim view");
            view.sendKey(key);
            return null;
        }
        if (key != null && HandlerUtil.getActivePart(event) instanceof NeovimPageEditor page) {
            LOG.fine(() -> "Ctrl-key command " + commandId + " -> " + key + " in " + page.getPartName());
            page.sendKey(key);
            return null;
        }
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        if (key == null || controller == null || controller.isClosed()) {
            LOG.warning("Ctrl-key command ran but there is nothing to send to: command=" + commandId
                    + ", key=" + key + ", controller=" + controller);
            return null;
        }
        // The key context is only active while this editor's text widget or its : box has focus, so
        // the attached controller is the right target; no further editor identity check is needed.
        LOG.fine(() -> "Ctrl-key command " + commandId + " -> " + key);
        controller.sendKey(key);
        return null;
    }
}
