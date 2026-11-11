// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;

/**
 * Window > Preferences > Neovim > Ctrl Keys, as Eclipse opens it: {@link CtrlKeysPage} on the
 * plug-in's preference store, which the {@link Activator} listens to, so the open editor takes a
 * change at once. {@code plugin.xml} registers this class.
 */
public class CtrlKeysPreferencePage extends CtrlKeysPage implements IWorkbenchPreferencePage {

    public CtrlKeysPreferencePage() {
        super(Activator.getDefault().getPreferenceStore());
    }

    @Override
    public void init(IWorkbench workbench) {
        // no-op: the store is set in the constructor
    }
}
