// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.prefs;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import io.github.jm6cnddxbwftaks.eclipseneovim.Activator;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.VimCtrlKeys;

/**
 * Window > Preferences > Neovim > Ctrl Keys: one check box per letter, ticked when
 * {@code Ctrl+letter} goes to Neovim and cleared when Eclipse keeps it.
 *
 * <h2>How a preference page works</h2>
 * {@code plugin.xml} registers this class under the extension point
 * {@code org.eclipse.ui.preferencePages}; its {@code category} attribute nests it below the main
 * Neovim page. Eclipse creates the page when the user first opens it and calls:
 * <ul>
 *   <li>{@link #createContents} once, to build the widgets;</li>
 *   <li>{@link #performDefaults} for "Restore Defaults";</li>
 *   <li>{@link #performOk} for "Apply" and "Apply and Close".</li>
 * </ul>
 *
 * <p>The values live in the plug-in's {@link IPreferenceStore}. Writing a value notifies the store's
 * listeners; the {@link Activator} listens and re-applies the choice to the open editor at once
 * (the editor activates one key-binding context per letter, see {@link VimCtrlKeys}).
 *
 * <p>This page is written by hand rather than with {@code FieldEditorPreferencePage} (used by
 * {@link NeovimPreferencePage}) because "Select All" and "Deselect All" have to set check boxes
 * from code, which field editors do not allow.
 */
public class CtrlKeysPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private static final int COLUMNS = 2;

    /** One check box per letter of {@link VimCtrlKeys#LETTERS}, in the same order. */
    private final Button[] boxes = new Button[VimCtrlKeys.LETTERS.length()];

    public CtrlKeysPreferencePage() {
        setDescription("Ticked Ctrl keys go to Neovim while it owns normal and visual mode; cleared ones "
                + "keep their Eclipse shortcut.\nIn insert mode all Ctrl keys stay with Eclipse. By default "
                + "Ctrl+S (save), Ctrl+W (close) and Ctrl+Z (undo) stay with Eclipse too.");
    }

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite page = new Composite(parent, SWT.NONE);
        page.setLayout(new GridLayout(1, false));

        Group keys = new Group(page, SWT.NONE);
        keys.setText("Send to Neovim");
        keys.setLayout(new GridLayout(COLUMNS, true));
        keys.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        // Column-major order, so the letters read A, B, C ... down the first column.
        int rows = (boxes.length + COLUMNS - 1) / COLUMNS;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                int index = column * rows + row;
                if (index < boxes.length) {
                    boxes[index] = createBox(keys, VimCtrlKeys.LETTERS.charAt(index));
                } else {
                    new Composite(keys, SWT.NONE); // empty cell
                }
            }
        }

        Composite buttons = new Composite(page, SWT.NONE);
        buttons.setLayout(new RowLayout());
        createButton(buttons, "Select All", true);
        createButton(buttons, "Deselect All", false);

        load(false);
        return page;
    }

    private static Button createBox(Composite parent, char letter) {
        Button box = new Button(parent, SWT.CHECK);
        box.setText("Ctrl+" + Character.toUpperCase(letter) + "   " + VimCtrlKeys.vimMeaning(letter));
        return box;
    }

    private void createButton(Composite parent, String label, boolean checked) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(label);
        button.addListener(SWT.Selection, event -> {
            for (Button box : boxes) {
                box.setSelection(checked);
            }
        });
    }

    /** Fills the check boxes from the store: the saved values, or the defaults. */
    private void load(boolean defaults) {
        IPreferenceStore store = getPreferenceStore();
        for (int i = 0; i < boxes.length; i++) {
            String key = PreferenceConstants.ctrlKeyToNeovim(VimCtrlKeys.LETTERS.charAt(i));
            boxes[i].setSelection(defaults ? store.getDefaultBoolean(key) : store.getBoolean(key));
        }
    }

    @Override
    protected void performDefaults() {
        load(true);
        super.performDefaults();
    }

    @Override
    public boolean performOk() {
        IPreferenceStore store = getPreferenceStore();
        for (int i = 0; i < boxes.length; i++) {
            store.setValue(PreferenceConstants.ctrlKeyToNeovim(VimCtrlKeys.LETTERS.charAt(i)), boxes[i].getSelection());
        }
        return true;
    }
}
