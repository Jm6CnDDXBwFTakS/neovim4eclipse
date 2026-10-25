// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WelcomeNote;

/**
 * Tells the Neovim toolbar button to redraw itself when what it shows has changed: Neovim attached
 * to an editor or detached from it, or the editor switched between Neovim and Eclipse mode.
 *
 * <p>Why a separate class: Eclipse does not poll a toolbar button. Its pressed state and tooltip
 * come from {@link ToggleNeovimModeHandler#updateElement}, which Eclipse calls only when asked to
 * ({@link ICommandService#refreshElements}); its greyed-out state comes from the handler's
 * enablement, which the handler must recompute and announce itself. The places that change the
 * state ({@link EditorAttachManager}, {@link NeovimEditorController}) call {@link #request()}; they
 * need not know the handler, which Eclipse creates lazily and may never create at all.
 *
 * <p>Any thread: the work runs later on the UI thread ({@code asyncExec}), after the change that
 * asked for it is complete (an attach sets its controller only after the controller has started).
 */
final class ToggleButtonRefresh {

    private static final Logger LOG = Logger.getLogger(ToggleButtonRefresh.class.getName());

    /** The live toggle handlers (normally one), each recomputing its enablement. */
    private static final List<Runnable> HANDLERS = new CopyOnWriteArrayList<>();

    private ToggleButtonRefresh() {
    }

    static void addHandler(Runnable recompute) {
        HANDLERS.add(recompute);
    }

    static void removeHandler(Runnable recompute) {
        HANDLERS.remove(recompute);
    }

    /** The button's state may have changed; redraw it soon. */
    static void request() {
        if (!PlatformUI.isWorkbenchRunning()) {
            return; // tests without a workbench, or shutting down
        }
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(ToggleButtonRefresh::refreshNow);
    }

    /** UI thread. */
    private static void refreshNow() {
        try {
            HANDLERS.forEach(Runnable::run); // grey out or enable
            if (!PlatformUI.isWorkbenchRunning()) {
                return;
            }
            ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commands != null) {
                commands.refreshElements(WelcomeNote.TOGGLE_COMMAND_ID, null); // pressed or raised, tooltip
            }
        } catch (RuntimeException e) { // the button looking stale must not break an attach
            LOG.log(Level.FINE, "Could not refresh the Neovim toolbar button", e);
        }
    }
}
