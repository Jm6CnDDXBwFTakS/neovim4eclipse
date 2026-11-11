// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimOption;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimProbe;

/**
 * Window → Preferences → Neovim, as Eclipse opens it: {@link NeovimPage} given the workbench's
 * parts. {@code plugin.xml} registers this class under {@code org.eclipse.ui.preferencePages}. It
 * adds three things, all workbench: the plug-in's preference store, the Neovim group's section with
 * its real probe and actions ({@link WorkbenchSectionActions}), and, after OK or Apply, a fresh look
 * at the toolbar button's and the menu's visibility ({@link ContributionVisibility}).
 */
public class NeovimPreferencePage extends NeovimPage implements IWorkbenchPreferencePage {

    /**
     * The text of the help's link, "Using a Neovim of your own", under the command in the Neovim
     * group: for tests ({@link NeovimStatusSection#HELP}). Only the link; the help's prose is in
     * the labels around it ({@code HELP_LEAD}, {@code HELP_TAIL}).
     */
    public static final String NEOVIM_HELP_LINK = NeovimStatusSection.HELP;

    public NeovimPreferencePage() {
        super(Activator.getDefault().getPreferenceStore(), group -> new NeovimStatusSection(group,
                NeovimOption::value, NvimProbe::describe, new WorkbenchSectionActions()));
    }

    @Override
    public void init(IWorkbench workbench) {
        // no-op: the store is set in the constructor
    }

    @Override
    public boolean performOk() {
        boolean ok = super.performOk();
        if (ok) {
            // Re-evaluate menu/toolbar visibility expressions
            ContributionVisibility.refresh();
        }
        return ok;
    }

    @Override
    protected void performApply() {
        super.performApply();
        ContributionVisibility.refresh();
    }
}
