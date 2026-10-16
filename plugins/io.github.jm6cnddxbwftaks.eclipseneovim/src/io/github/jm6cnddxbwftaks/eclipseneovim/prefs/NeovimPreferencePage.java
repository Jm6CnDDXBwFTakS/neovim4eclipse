// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.prefs;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import io.github.jm6cnddxbwftaks.eclipseneovim.Activator;

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
        setDescription("Configure the Eclipse Neovim integration.");
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
    }

    @Override
    public boolean performOk() {
        boolean ok = super.performOk();
        if (ok) {
            // Re-evaluate menu/toolbar visibility expressions
            ToolbarVisibility.refresh();
        }
        return ok;
    }

    @Override
    protected void performApply() {
        super.performApply();
        ToolbarVisibility.refresh();
    }
}
