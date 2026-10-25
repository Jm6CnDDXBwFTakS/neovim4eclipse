// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.Map;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ToggleButtonState;

/**
 * Toggles between Neovim key handling and plain Eclipse editing on the active editor (the Neovim
 * toolbar button, its menu checkbox and its key binding). Neovim stays attached in Eclipse mode,
 * so switching back is instant. See {@link AttachHandler} for how handlers work.
 *
 * <h2>Pressed, raised, greyed out</h2>
 * The toolbar button and the menu entry are {@code style="toggle"} items, which Eclipse draws
 * pressed (checked) or raised. Two handler features drive them:
 * <ul>
 *   <li><b>{@link IElementUpdater}</b>: Eclipse calls {@link #updateElement} for every item bound to
 *       this command (the button, the menu entry) when someone calls
 *       {@code ICommandService.refreshElements}; the handler sets each item's checked state and
 *       tooltip there.</li>
 *   <li><b>Enablement</b>: {@link #setBaseEnabled} greys every item out at once and also makes the
 *       key binding do nothing, while no editor has Neovim attached.</li>
 * </ul>
 * {@link ToggleButtonRefresh} triggers both when Neovim attaches, detaches or switches mode; what
 * they show is decided by {@link ToggleButtonState}.
 */
public class ToggleNeovimModeHandler extends AbstractHandler implements IElementUpdater {

    private final Runnable recompute = this::recomputeEnabled;

    public ToggleNeovimModeHandler() {
        recomputeEnabled();
        ToggleButtonRefresh.addHandler(recompute);
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        NeovimEditorController controller = activeController();
        if (controller == null) {
            // Normally unreachable (the command is disabled then), but a refresh may still be on its way.
            MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "Neovim",
                    "Neovim is not attached to this editor. It attaches to text editors by itself; if it"
                            + " did not, it may have failed to start (Window > Preferences > Neovim names the"
                            + " executable). To try again: Neovim: Attach to Active Editor (Ctrl+Shift+N), or"
                            + " Quick Access (Ctrl+3).");
            return null;
        }
        // No confirmation dialog: the button and the status line show the resulting mode.
        controller.toggleNeovimMode(); // asks ToggleButtonRefresh to redraw the button
        return null;
    }

    /**
     * Called by Eclipse for each toolbar button and menu entry of this command, on the UI thread.
     * Without it, a toggle item would flip its own pressed state on every click and drift out of
     * step with the editor (switch editors, and the button would still show the old one's mode).
     */
    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        ToggleButtonState state = state();
        element.setChecked(state.checked());
        element.setTooltip(state.tooltip());
    }

    @Override
    public void dispose() {
        ToggleButtonRefresh.removeHandler(recompute);
        super.dispose();
    }

    /** Greys the command out, or enables it, for the editor now attached; fires a change if it changed. */
    private void recomputeEnabled() {
        setBaseEnabled(activeController() != null);
    }

    private static ToggleButtonState state() {
        NeovimEditorController controller = activeController();
        return ToggleButtonState.of(controller != null,
                controller != null && controller.isNeovimModeActive(), WelcomeNotice.toggleKey());
    }

    private static NeovimEditorController activeController() {
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        return controller == null || controller.isClosed() ? null : controller;
    }
}
