// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.util.Objects;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.NvimExecutable;

/**
 * Tells the user, once, that Neovim could not be started, and offers to open the preference page
 * where its path is set.
 *
 * <p>Attaching is attempted every time a text editor is activated, so without a guard a missing
 * {@code nvim} would raise a dialog on every editor switch. The notice is shown once per configured
 * executable: again only after the preference changes, or after a start succeeded and a later one
 * failed. Before this class, the failure only reached the log, and the plug-in silently did nothing.
 */
final class StartFailureNotice {

    /** The executable whose failure was last reported; {@code null} after a successful start. UI thread only. */
    private static String reportedFor;

    private StartFailureNotice() {
    }

    /** Neovim started: a later failure will be reported again. */
    static void clear() {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed()) {
            display.asyncExec(() -> reportedFor = null);
        }
    }

    /**
     * Shows the notice unless it was already shown for this executable. Safe to call from any thread.
     *
     * @param configured the preference value (command name or path)
     * @param error      why the start failed
     */
    static void report(String configured, Throwable error) {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            if (Objects.equals(reportedFor, configured)) {
                return;
            }
            reportedFor = configured;
            show(configured, error);
        });
    }

    private static void show(String configured, Throwable error) {
        Shell shell = PlatformUI.isWorkbenchRunning() && PlatformUI.getWorkbench().getActiveWorkbenchWindow() != null
                ? PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell()
                : null;
        String message = "Eclipse Neovim could not start Neovim, so editors keep their normal Eclipse keys.\n\n"
                + "Executable: " + NvimExecutable.resolve(configured) + "\n"
                + "Error: " + rootMessage(error) + "\n\n"
                + "Install Neovim, or set the full path to nvim on the Neovim preference page. "
                + "Eclipse started from the Dock, a desktop menu or a PDE launch may not see your shell's PATH.";
        MessageDialog dialog = new MessageDialog(shell, "Neovim not started", null, message, MessageDialog.ERROR,
                0, "Open Preferences", "Close");
        if (dialog.open() == 0) {
            OpenPreferencesHandler.open(shell);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
