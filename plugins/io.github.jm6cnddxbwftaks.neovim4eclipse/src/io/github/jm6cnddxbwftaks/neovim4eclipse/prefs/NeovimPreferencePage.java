// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;

/**
 * Window → Preferences → Neovim.
 *
 * <p>A {@link FieldEditorPreferencePage} builds the page from <em>field editors</em>: each one
 * ({@link StringFieldEditor}, {@link BooleanFieldEditor}, ...) shows one preference, loads it from the
 * store, writes it back on Apply, and restores its default on Restore Defaults, with no further
 * code. {@code plugin.xml} registers the page under {@code org.eclipse.ui.preferencePages}; the
 * Status Bar ({@link StatusBarPreferencePage}) and Ctrl Keys ({@link CtrlKeysPreferencePage}) pages
 * are nested under it with a {@code category}.
 */
public class NeovimPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    public NeovimPreferencePage() {
        super(GRID);
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("Configure the Neovim for Eclipse integration.");
    }

    @Override
    public void init(IWorkbench workbench) {
        // no-op
    }

    @Override
    protected void createFieldEditors() {
        // Path or command name — FileFieldEditor is awkward for PATH names like "nvim",
        // so use a string field with a note; users can paste an absolute path.
        StringFieldEditor nvimPath = new StringFieldEditor(
                PreferenceConstants.NVIM_EXECUTABLE,
                "Neovim executable:",
                getFieldEditorParent());
        nvimPath.setEmptyStringAllowed(false);
        nvimPath.getTextControl(getFieldEditorParent()).setToolTipText(
                "Command name on PATH (e.g. nvim) or absolute path to the binary. "
                        + "Restart the Neovim session (detach/reattach or restart Eclipse) after changing.");
        addField(nvimPath);

        addField(new BooleanFieldEditor(
                PreferenceConstants.SHOW_TOOLBAR,
                "Show NVIM button on the main toolbar",
                getFieldEditorParent()));

        // Hidden, its two entries stay reachable: Window > Preferences > Neovim, and the toggle's
        // key binding, toolbar button and Quick Access (Ctrl+3).
        addField(new BooleanFieldEditor(
                PreferenceConstants.SHOW_MENU,
                "Show Neovim menu in the menu bar",
                getFieldEditorParent()));

        // Messages and errors always appear in a line at the bottom of the editor; this adds a flash.
        addField(new BooleanFieldEditor(
                PreferenceConstants.FLASH_ON_ERROR,
                "Flash the editor on errors",
                getFieldEditorParent()));

        // The welcome after installing, and the short note after an update to a new minor version.
        addField(new BooleanFieldEditor(
                PreferenceConstants.SHOW_WELCOME,
                "Show a welcome after installing, and a note after updates to a new minor version",
                getFieldEditorParent()));
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
