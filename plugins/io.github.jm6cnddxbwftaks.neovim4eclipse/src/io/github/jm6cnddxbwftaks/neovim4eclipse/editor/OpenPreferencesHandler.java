// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Command "Neovim: Preferences" (the Neovim menu): opens Window > Preferences on the Neovim page
 * with its sub-pages (Status Bar, Ctrl Keys) visible.
 *
 * <p>Eclipse's own command ({@code org.eclipse.ui.window.preferences} with a page id) selects the
 * page but leaves it collapsed in the tree. This handler builds the same dialog with
 * {@link PreferencesUtil#createPreferenceDialogOn}, which also creates its window, then expands the
 * selected tree node before showing it. {@link #open(Shell)} is also used by the "Neovim not started" notice.
 */
public final class OpenPreferencesHandler extends AbstractHandler {

    /** Id of the main Neovim preference page (plugin.xml). */
    public static final String PAGE_ID = "io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.page";

    @Override
    public Object execute(ExecutionEvent event) {
        open(HandlerUtil.getActiveShell(event));
        return null;
    }

    /** Opens the preferences on the Neovim page, expanded. UI thread only. */
    public static void open(Shell shell) {
        create(shell).open();
    }

    /** Builds the dialog without opening it (also for tests). */
    static PreferenceDialog create(Shell shell) {
        // createPreferenceDialogOn already creates the dialog's window and tree. Calling create() again
        // would build a second window, leaving the Neovim page's widgets in the first, hidden one.
        PreferenceDialog dialog = PreferencesUtil.createPreferenceDialogOn(shell, PAGE_ID, null, null);
        if (dialog.getShell() == null) {
            dialog.create();
        }
        TreeViewer tree = dialog.getTreeViewer();
        if (tree != null) {
            Object selected = tree.getStructuredSelection().getFirstElement();
            if (selected != null) {
                tree.setExpandedState(selected, true);
            }
        }
        return dialog;
    }
}
