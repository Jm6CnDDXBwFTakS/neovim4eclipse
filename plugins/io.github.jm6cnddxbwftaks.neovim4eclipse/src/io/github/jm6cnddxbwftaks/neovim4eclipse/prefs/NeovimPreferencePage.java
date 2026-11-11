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
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimOption;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimProbe;

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
 * The page has two parts: <em>Neovim</em> (the note saying which Neovim runs, Get Neovim, and the
 * help: {@link NeovimStatusSection}) and <em>Workbench</em> (the check boxes). Each is a
 * {@link Group}, whose border shows which lines belong together. A field editor is created on the
 * group instead of {@link #getFieldEditorParent()}, which then holds just the two groups. See
 * {@link #adjustGridLayout} for the one catch.
 *
 * <p>The Neovim group has no field: until 0.2.1 it held the "Neovim program" field, and the
 * program is now the startup option {@code -Dneovim4eclipse.nvim} ({@code util/NeovimOption}).
 */
public class NeovimPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    /**
     * The text of the help's link, "Using a Neovim of your own", under the command in the Neovim
     * group: for tests ({@link NeovimStatusSection#HELP}). Only the link; the help's prose is in
     * the labels around it ({@code HELP_LEAD}, {@code HELP_TAIL}).
     */
    public static final String NEOVIM_HELP_LINK = NeovimStatusSection.HELP;

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
        program = group("Neovim");
        program.setLayout(new GridLayout()); // no field editor sets one here; adjustGridLayout fills it in
        // Which Neovim runs, Get Neovim, and the help.
        new NeovimStatusSection(program, NeovimOption::value, NvimProbe::describe, new WorkbenchSectionActions());

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
     * {@code super} first gives every field editor the widest field's column count; then the page
     * gets one column (the groups, one under the other), and the Workbench group gets those columns
     * back. Both groups get SWT's usual margins so their lines do not touch the border, and the row
     * spacing; the Neovim group keeps its one column, as it holds no field editor. Spacing in dialog units follows the dialog font, as the rest of the dialog does.
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
                layout.numColumns = group == program ? 1 : columns;
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
