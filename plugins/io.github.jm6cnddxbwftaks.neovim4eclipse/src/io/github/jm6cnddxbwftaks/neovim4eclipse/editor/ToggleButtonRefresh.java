// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.e4.core.services.events.IEventBroker;
import org.eclipse.e4.ui.model.application.MApplicationElement;
import org.eclipse.e4.ui.model.application.commands.MCommand;
import org.eclipse.e4.ui.model.application.ui.menu.MHandledItem;
import org.eclipse.e4.ui.workbench.Selector;
import org.eclipse.e4.ui.workbench.UIEvents;
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
 * enablement, which the handler must recompute and announce itself, and which the toolbar then
 * re-reads only when asked to (see {@link #requestEnablementUpdate}). The places that change the
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
            requestEnablementUpdate();
            ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commands != null) {
                commands.refreshElements(WelcomeNote.TOGGLE_COMMAND_ID, null); // pressed or raised, tooltip
            }
        } catch (RuntimeException e) { // the button looking stale must not break an attach
            LOG.log(Level.FINE, "Could not refresh the Neovim toolbar button", e);
        }
    }

    /**
     * Asks the toolbar to re-read the button's enablement from the handler, now that the handler has
     * changed it.
     *
     * <p>Why it is needed: a toolbar item (Eclipse 4's {@code HandledContributionItem}) keeps its own
     * copy of "enabled". The handler's {@code setBaseEnabled} makes the item redraw, but from that
     * copy; the copy is recomputed (by asking the handler) only on
     * {@link UIEvents#REQUEST_ENABLEMENT_UPDATE_TOPIC}, or when the window's active contexts,
     * selection or shell change. Without this request the button greyed out only when such an
     * unrelated change happened to come after the handler's: after <em>Detach</em> it usually did
     * (Detach changes the key binding contexts), but on a busy machine the order could flip and the
     * button stayed enabled (it failed {@code ToggleButtonEndToEndTest} on the v0.1.9 release
     * build). The toolbar re-reads at most every 100 ms, so the button greys out shortly after.
     *
     * <p>Only the toggle command's items are re-read (a {@link Selector}), not every toolbar item.
     */
    private static void requestEnablementUpdate() {
        IEventBroker events = PlatformUI.getWorkbench().getService(IEventBroker.class);
        if (events != null) {
            Selector toggleItems = ToggleButtonRefresh::isToggleItem;
            events.send(UIEvents.REQUEST_ENABLEMENT_UPDATE_TOPIC, toggleItems);
        }
    }

    /** Whether a toolbar or menu element runs the toggle command (the button, the menu entry). */
    private static boolean isToggleItem(MApplicationElement element) {
        if (element instanceof MHandledItem item) {
            MCommand command = item.getCommand();
            return command != null && WelcomeNote.TOGGLE_COMMAND_ID.equals(command.getElementId());
        }
        return false;
    }
}
