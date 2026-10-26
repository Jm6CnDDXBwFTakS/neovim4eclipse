// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.lang.ref.WeakReference;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StartupErrorReport;

/**
 * Warns, once per Neovim session, that Neovim started but the user's configuration reported errors
 * ({@code NeovimConnection.waitForStartup} reads them from {@code :messages}). Before the plug-in
 * handled it, such an error left Neovim at a "Press ENTER" prompt and made the start time out, and the editor kept Eclipse's keys with only a
 * "Neovim not started: TimeoutException" to go on.
 *
 * <p>The text is {@link StartupErrorReport} (unit-tested); this class only moves to the UI thread,
 * remembers which session it warned about, and shows the dialog.
 */
final class StartupErrorNotice {

    /** The session warned about last; a new Neovim (after a restart) is warned about again. UI thread only. */
    private static WeakReference<NeovimSession> warned = new WeakReference<>(null);

    private StartupErrorNotice() {
    }

    /** Neovim started for an editor: warn if its configuration reported errors. Safe from any thread. */
    static void afterStart(NeovimSession session) {
        String errors = session.startupErrors();
        Display display = Display.getDefault();
        if (errors.isEmpty() || display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            if (warned.get() == session) {
                return;
            }
            warned = new WeakReference<>(session);
            MessageDialog.openWarning(activeShell(), StartupErrorReport.TITLE, StartupErrorReport.message(errors));
        });
    }

    private static Shell activeShell() {
        if (!PlatformUI.isWorkbenchRunning()) {
            return null;
        }
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        return window == null ? null : window.getShell();
    }
}
