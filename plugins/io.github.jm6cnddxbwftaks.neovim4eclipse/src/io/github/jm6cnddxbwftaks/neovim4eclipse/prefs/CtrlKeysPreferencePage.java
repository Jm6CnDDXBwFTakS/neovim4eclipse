// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

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
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimCtrlKeys;

/**
 * Window > Preferences > Neovim > Ctrl Keys: one row per letter, with two check boxes, each with
 * Vim's meaning beside it: <em>Normal</em> (normal and visual mode, the {@code :} box) and
 * <em>Insert</em> (insert and replace mode, whether Eclipse types or Neovim drives the insert). A ticked box sends
 * {@code Ctrl+letter} to Neovim there; a cleared one leaves Eclipse's shortcut. Example row:
 *
 * <pre>
 *   Ctrl+W   [ ] window commands        [x] delete the word before
 * </pre>
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

    /**
     * One column of 26 rows, about 500 px wide: two halves side by side were 950 px, wider than the
     * Preferences dialog's page area, which then grew sideways. The page scrolls
     * up and down instead, the usual direction.
     */
    private static final int HALVES = 1;

    /** Widgets per row and half: the key's name, the Normal box, the Insert box. */
    private static final int CELLS = 3;

    /** One Normal and one Insert check box per letter of {@link VimCtrlKeys#LETTERS}, in the same order. */
    private final Button[] normal = new Button[VimCtrlKeys.LETTERS.length()];
    private final Button[] insert = new Button[VimCtrlKeys.LETTERS.length()];

    public CtrlKeysPreferencePage() {
        setDescription("A ticked box sends the Ctrl key to Neovim; a cleared one keeps Eclipse's shortcut.\n"
                + "Normal: normal and visual mode, and the : box. Insert: insert mode; when Eclipse types,\n"
                + "Neovim gets the typed text first, so its key acts on it.");
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
        GridLayout grid = new GridLayout(HALVES * CELLS, false);
        grid.horizontalSpacing = 12;
        keys.setLayout(grid);
        keys.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        for (int half = 0; half < HALVES; half++) {
            header(keys, "");
            header(keys, "Normal");
            header(keys, "Insert");
        }
        // Column-major order, so the letters read A, B, C ... down each half.
        int rows = (normal.length + HALVES - 1) / HALVES;
        for (int row = 0; row < rows; row++) {
            for (int half = 0; half < HALVES; half++) {
                int index = half * rows + row;
                if (index < normal.length) {
                    char letter = VimCtrlKeys.LETTERS.charAt(index);
                    Label name = new Label(keys, SWT.NONE);
                    name.setText("Ctrl+" + Character.toUpperCase(letter));
                    normal[index] = box(keys, VimCtrlKeys.vimMeaning(letter));
                    insert[index] = box(keys, VimCtrlKeys.insertMeaning(letter));
                } else {
                    for (int cell = 0; cell < CELLS; cell++) {
                        new Label(keys, SWT.NONE); // empty cell
                    }
                }
            }
        }

        Composite buttons = new Composite(page, SWT.NONE);
        buttons.setLayout(new RowLayout());
        createButton(buttons, "Select All Normal", normal, true);
        createButton(buttons, "Deselect All Normal", normal, false);
        createButton(buttons, "Select All Insert", insert, true);
        createButton(buttons, "Deselect All Insert", insert, false);

        load(false);
        return page;
    }

    private static void header(Composite parent, String text) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
        label.setFont(org.eclipse.jface.resource.JFaceResources.getBannerFont());
    }

    private static Button box(Composite parent, String meaning) {
        Button box = new Button(parent, SWT.CHECK);
        box.setText(meaning);
        return box;
    }

    private static void createButton(Composite parent, String label, Button[] boxes, boolean checked) {
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
        for (int i = 0; i < normal.length; i++) {
            char letter = VimCtrlKeys.LETTERS.charAt(i);
            String n = PreferenceConstants.ctrlKeyToNeovim(letter);
            String in = PreferenceConstants.ctrlKeyInInsert(letter);
            normal[i].setSelection(defaults ? store.getDefaultBoolean(n) : store.getBoolean(n));
            insert[i].setSelection(defaults ? store.getDefaultBoolean(in) : store.getBoolean(in));
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
        for (int i = 0; i < normal.length; i++) {
            char letter = VimCtrlKeys.LETTERS.charAt(i);
            store.setValue(PreferenceConstants.ctrlKeyToNeovim(letter), normal[i].getSelection());
            store.setValue(PreferenceConstants.ctrlKeyInInsert(letter), insert[i].getSelection());
        }
        return true;
    }
}
