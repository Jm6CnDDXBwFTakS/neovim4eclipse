// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse;

import org.eclipse.ui.IStartup;

import io.github.jm6cnddxbwftaks.neovim4eclipse.editor.EditorAttachManager;

/**
 * Ensures auto-attach listeners are registered after the workbench starts, even if the bundle was
 * not yet activated.
 *
 * <p>Registered in {@code plugin.xml} under {@code org.eclipse.ui.startup}. The workbench calls
 * {@link #earlyStartup()} once, on a background thread, after its windows are open. Loading this
 * class also starts the bundle (lazy activation), so {@link Activator#start} has run by then.
 * Without this hook, nothing would load the bundle until the user clicked a Neovim menu item, and
 * editors would open without Neovim. Users can disable early start-up per plug-in under
 * Window &gt; Preferences &gt; General &gt; Startup and Shutdown.
 */
public class EarlyStartup implements IStartup {

    @Override
    public void earlyStartup() {
        EditorAttachManager.getInstance().installWorkbenchListeners();
    }
}
