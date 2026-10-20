// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;

/**
 * Window > Preferences > Neovim > Status Bar: which parts of Vim's status information the Eclipse
 * status bar shows. Changes apply at once (the {@link Activator} listens to the preference store).
 *
 * <p>Like {@link NeovimPreferencePage}, a {@link FieldEditorPreferencePage}: each check box is a
 * {@link BooleanFieldEditor} that loads, stores and restores its preference by itself.
 */
public class StatusBarPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    public StatusBarPreferencePage() {
        super(GRID);
        setDescription("Parts of Vim's status information shown in the Eclipse status bar. Messages and errors appear at the bottom of the editor.");
    }

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
    }

    @Override
    protected void createFieldEditors() {
        addField(new BooleanFieldEditor(PreferenceConstants.STATUS_ITEM_MODE,
                "mode  (-- INSERT --, recording @q)", getFieldEditorParent()));
        addField(new BooleanFieldEditor(PreferenceConstants.STATUS_ITEM_CMD,
                "cmd  (pending command, e.g. 2d)", getFieldEditorParent()));
        addField(new BooleanFieldEditor(PreferenceConstants.STATUS_ITEM_STATUSLINE,
                "statusline  (Neovim's 'statusline')", getFieldEditorParent()));
    }
}
