// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import org.eclipse.swt.widgets.Shell;

/**
 * The preference page's {@link NeovimStatusSection.Actions} in Eclipse: each call goes straight to
 * {@link GetNeovim} or {@link ExternalBrowser}, and nothing else. It exists so the section itself
 * needs no workbench (the SWT tests build it with actions of their own), and it adds no step of its
 * own: Get Neovim behaves the same from the page, the Neovim menu and the "Neovim not started"
 * notice, which call {@link GetNeovim} directly.
 */
final class WorkbenchSectionActions implements NeovimStatusSection.Actions {

    @Override
    public void openGetNeovim(Shell shell) {
        GetNeovim.open(shell);
    }

    @Override
    public void addInstallListener(Runnable listener) {
        GetNeovim.addListener(listener);
    }

    @Override
    public void removeInstallListener(Runnable listener) {
        GetNeovim.removeListener(listener);
    }

    @Override
    public void openLink(String url) {
        ExternalBrowser.open(url);
    }
}
