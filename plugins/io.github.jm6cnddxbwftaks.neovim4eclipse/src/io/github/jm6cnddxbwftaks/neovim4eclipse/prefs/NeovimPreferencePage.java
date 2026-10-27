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

    /** The README section on where the plug-in looks for Neovim, opened by the link in the field's help. */
    public static final String FINDING_NEOVIM_URL = "https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse#finding-neovim";

    /**
     * The help under the Neovim field, after the note: what the field takes, then a link to the
     * README section that lists the default folders. {@code <a>} marks the clickable part.
     */
    public static final String NEOVIM_FIELD_HELP = "Leave blank to search the default folders, or enter a folder "
            + "that holds Neovim, or a direct path to an nvim executable. <a>How Neovim for Eclipse finds Neovim</a>";

    public NeovimPreferencePage() {
        super(GRID);
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("Configure Neovim for Eclipse integration.");
    }

    @Override
    public void init(IWorkbench workbench) {
        // no-op
    }

    @Override
    protected void createFieldEditors() {
        // A folder that holds Neovim, the absolute path of the program, or blank to search. Under it:
        // the note saying which Neovim that finds, then the help (NeovimLocationFieldEditor).
        addField(new NeovimLocationFieldEditor(
                PreferenceConstants.NVIM_EXECUTABLE,
                "Neovim folder or program:",
                getFieldEditorParent()));

        addField(new BooleanFieldEditor(
                PreferenceConstants.SHOW_TOOLBAR,
                "Show the Neovim button on the main toolbar",
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

        // Eclipse's errors and warnings as Neovim diagnostics: ]d and [d move between them.
        addField(new BooleanFieldEditor(
                PreferenceConstants.SEND_PROBLEMS,
                "Send Eclipse's problems to Neovim (]d and [d jump to the next and previous one)",
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
