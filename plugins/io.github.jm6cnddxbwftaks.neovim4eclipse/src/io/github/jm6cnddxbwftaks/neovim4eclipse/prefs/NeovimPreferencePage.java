// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Group;
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
 *
 * <h2>Two group boxes</h2>
 * The page has two parts: <em>Neovim program</em> (the field, the note saying which Neovim it finds,
 * and the help) and <em>Workbench</em> (the check boxes). Each is a {@link Group}, whose border shows
 * which lines belong together; before, only an empty row separated the help from the check boxes.
 * A field editor is created on the group instead of {@link #getFieldEditorParent()}, which then holds
 * just the two groups. See {@link #adjustGridLayout} for the one catch.
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

    private Group program;
    private Group workbench;

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
        program = group("Neovim program");
        // A folder that holds Neovim, the absolute path of the program, or blank to search. Under it:
        // the note saying which Neovim that finds, then the help (NeovimLocationFieldEditor).
        addField(new NeovimLocationFieldEditor(
                PreferenceConstants.NVIM_EXECUTABLE,
                "Neovim folder or program:",
                program));

        workbench = group("Workbench");
        addField(new BooleanFieldEditor(
                PreferenceConstants.SHOW_TOOLBAR,
                "Show the Neovim button on the main toolbar",
                workbench));

        // Hidden, its two entries stay reachable: Window > Preferences > Neovim, and the toggle's
        // key binding, toolbar button and Quick Access (Ctrl+3).
        addField(new BooleanFieldEditor(
                PreferenceConstants.SHOW_MENU,
                "Show Neovim menu in the menu bar",
                workbench));

        // Messages and errors always appear in a line at the bottom of the editor; this adds a flash.
        addField(new BooleanFieldEditor(
                PreferenceConstants.FLASH_ON_ERROR,
                "Flash the editor on errors",
                workbench));

        // The welcome after installing, and the short note after an update to a new minor version.
        addField(new BooleanFieldEditor(
                PreferenceConstants.SHOW_WELCOME,
                "Show a welcome after installing, and a note after updates to a new minor version",
                workbench));
    }

    /** A titled group box on the page, filling its width; {@link #adjustGridLayout} sets its grid. */
    private Group group(String title) {
        Group group = new Group(getFieldEditorParent(), SWT.NONE);
        group.setText(title);
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        return group;
    }

    /**
     * Lays out the page and its two groups once every field editor exists.
     *
     * <p>The catch: a field editor sets a fresh {@link GridLayout} on its parent when it is created
     * ({@code FieldEditor.createControl}), with no margins and as many columns as it has controls, and
     * {@link FieldEditorPreferencePage} adjusts the columns only on {@link #getFieldEditorParent()}. So
     * {@code super} first gives every field editor the widest field's column count (3: label, text,
     * Browse); then the page gets one column (the groups, one under the other), and each group gets
     * those columns back, with SWT's usual margins so the fields do not touch the border, and the row
     * spacing. Spacing in dialog units follows the dialog font, as the rest of the dialog does.
     */
    @Override
    protected void adjustGridLayout() {
        super.adjustGridLayout();
        if (!(getFieldEditorParent().getLayout() instanceof GridLayout page)) {
            return; // not FieldEditorPreferencePage's grid: leave the page as Eclipse laid it out
        }
        int columns = page.numColumns;
        page.numColumns = 1;
        page.verticalSpacing = convertVerticalDLUsToPixels(PageLayout.GROUP_SPACING_DLUS);
        GridLayout usual = new GridLayout(); // SWT's default margins
        for (Group group : new Group[] { program, workbench }) {
            if (group.getLayout() instanceof GridLayout layout) {
                layout.numColumns = columns;
                layout.marginWidth = usual.marginWidth;
                layout.marginHeight = usual.marginHeight;
                layout.verticalSpacing = convertVerticalDLUsToPixels(PageLayout.ROW_SPACING_DLUS);
            }
        }
    }

    /** The two group boxes, top first; for tests. */
    Group[] groups() {
        return new Group[] { program, workbench };
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
