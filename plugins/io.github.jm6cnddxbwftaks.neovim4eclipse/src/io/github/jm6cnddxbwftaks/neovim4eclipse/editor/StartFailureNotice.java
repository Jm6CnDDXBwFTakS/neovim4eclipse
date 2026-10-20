// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StartFailureReport;

/**
 * Tells the user, once, that Neovim could not be started, and offers to open the preference page
 * where its path is set.
 *
 * <p>Attaching is attempted every time a text editor is activated, so without a guard a missing
 * {@code nvim} would raise a dialog on every editor switch. The notice is shown once per configured
 * executable: again only after the preference changes, or after a start succeeded and a later one
 * failed. Before this class, the failure only reached the log, and the plug-in silently did nothing.
 *
 * <p>That decision and the text are in {@link StartFailureReport} (unit-tested, no Eclipse); this
 * class only moves to the UI thread and shows the dialog.
 */
final class StartFailureNotice {

    /** Used on the UI thread only, so it needs no locking. */
    private static final StartFailureReport REPORT = new StartFailureReport();

    private StartFailureNotice() {
    }

    /** Neovim started: a later failure will be reported again. */
    static void clear() {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed()) {
            display.asyncExec(REPORT::started);
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
            if (REPORT.shouldReport(configured)) {
                show(configured, error);
            }
        });
    }

    private static void show(String configured, Throwable error) {
        Shell shell = PlatformUI.isWorkbenchRunning() && PlatformUI.getWorkbench().getActiveWorkbenchWindow() != null
                ? PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell()
                : null;
        String message = StartFailureReport.message(NvimExecutable.resolve(configured), error);
        MessageDialog dialog = new MessageDialog(shell, StartFailureReport.TITLE, null, message, MessageDialog.ERROR,
                0, "Open Preferences", "Close");
        if (dialog.open() == 0) {
            OpenPreferencesHandler.open(shell);
        }
    }
}
