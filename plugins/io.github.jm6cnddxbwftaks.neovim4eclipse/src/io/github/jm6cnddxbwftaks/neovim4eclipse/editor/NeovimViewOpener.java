// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewText;

/**
 * Opens the Neovim view ({@link NeovimView}) with text the editing Neovim produced:
 * {@code :messages} and {@code :checkhealth} ({@code NeovimSession.addViewTextListener}, which
 * {@code Activator} points here when it starts the session). The view takes the keyboard, as
 * Vim's {@code :messages} pager and {@code :checkhealth} window do; it opens in the active
 * workbench window, at the place Eclipse keeps for it ({@code plugin.xml}: with Console and
 * Problems, at first).
 *
 * <p>Example: {@code :messages} typed in an editor: the view opens, titled "Neovim: messages",
 * with the editor's colors, the cursor on the newest message.
 */
public final class NeovimViewOpener {

    private static final Logger LOG = Logger.getLogger(NeovimViewOpener.class.getName());

    private NeovimViewOpener() {
    }

    /** Shows {@code text} in the Neovim view; any thread (the RPC reader thread, usually). */
    public static void show(ViewText text) {
        if (!PlatformUI.isWorkbenchRunning()) {
            return;
        }
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.asyncExec(() -> showOnUi(text));
    }

    private static void showOnUi(ViewText text) {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) {
            IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
            window = windows.length > 0 ? windows[0] : null;
        }
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        if (page == null) {
            LOG.fine(() -> "No workbench page to show " + text.title() + " in");
            return;
        }
        IEditorPart editor = page.getActiveEditor();
        int foreground = 0x000000;
        int background = 0xFFFFFF;
        if (editor != null && editor.getAdapter(Control.class) instanceof StyledText styled && !styled.isDisposed()) {
            foreground = rgbOf(styled.getForeground());
            background = rgbOf(OverlayFrame.intendedBackground(styled));
        }
        try {
            if (page.showView(NeovimView.ID) instanceof NeovimView view) {
                view.show(text, editor, foreground, background);
            }
        } catch (PartInitException e) {
            LOG.log(Level.WARNING, "Could not open the Neovim view", e);
        }
    }

    private static int rgbOf(Color color) {
        return (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
    }
}
