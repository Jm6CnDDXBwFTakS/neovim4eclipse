// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import java.util.function.Consumer;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;

/**
 * The body of Window → Preferences → Neovim, in JFace alone; {@link NeovimPreferencePage} is the
 * page Eclipse opens (why two classes: {@code package-info}).
 *
 * <p>A {@link FieldEditorPreferencePage} builds the page from <em>field editors</em>: each one
 * ({@link StringFieldEditor}, {@link BooleanFieldEditor}, ...) shows one preference, loads it from the
 * store, writes it back on Apply, and restores its default on Restore Defaults, with no further
 * code. The Status Bar ({@link StatusBarPage}) and Ctrl Keys ({@link CtrlKeysPage}) pages are
 * nested under it ({@code plugin.xml}, {@code category}).
 *
 * <h2>Two group boxes</h2>
 * The page has two parts: <em>Neovim</em> (the note saying which Neovim runs, Get Neovim, and the
 * help: {@link NeovimStatusSection}, which the constructor's {@code neovimGroup} puts there) and
 * <em>Workbench</em> (the check boxes). Each is a {@link Group}, whose border shows which lines
 * belong together. A field editor is created on the group instead of
 * {@link #getFieldEditorParent()}, which then holds just the two groups. See {@link #adjustGridLayout}
 * for the one catch.
 *
 * <p>The Neovim group has no field: until 0.2.1 it held the "Neovim program" field, and the
 * program is now the startup option {@code -Dneovim4eclipse.nvim} ({@code util/NeovimOption}).
 */
class NeovimPage extends FieldEditorPreferencePage {

    /** Fills the Neovim group, a one-column grid: the page passes a {@link NeovimStatusSection}. */
    private final Consumer<Composite> neovimGroup;

    private Group program;
    private Group workbench;

    /**
     * A page on {@code store}. Example, as a test builds it:
     * {@code new NeovimPage(new PreferenceStore(), group -> new NeovimStatusSection(group, ...))}.
     */
    NeovimPage(IPreferenceStore store, Consumer<Composite> neovimGroup) {
        super(GRID);
        this.neovimGroup = neovimGroup;
        setPreferenceStore(store);
        setDescription("Configure Neovim for Eclipse integration.");
    }

    @Override
    protected void createFieldEditors() {
        program = group("Neovim");
        program.setLayout(new GridLayout()); // no field editor sets one here; adjustGridLayout fills it in
        neovimGroup.accept(program); // which Neovim runs, Get Neovim, and the help

        workbench = group("Workbench");
        addField(new BooleanFieldEditor(
                PreferenceConstants.SHOW_TOGGLE_BUTTON,
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
     * spacing; the Neovim group keeps its one column, as it holds no field editor. Spacing in dialog
     * units follows the dialog font, as the rest of the dialog does.
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
}
