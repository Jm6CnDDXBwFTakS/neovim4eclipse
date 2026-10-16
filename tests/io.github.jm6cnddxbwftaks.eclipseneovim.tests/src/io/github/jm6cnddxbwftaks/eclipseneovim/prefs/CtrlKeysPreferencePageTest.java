// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.eclipseneovim.Activator;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.VimCtrlKeys;

/** The Ctrl Keys page: its buttons, Restore Defaults, and what Apply stores. */
class CtrlKeysPreferencePageTest {

    private Shell shell;
    private CtrlKeysPreferencePage page;
    private IPreferenceStore store;

    @BeforeEach
    void open() {
        store = Activator.getDefault().getPreferenceStore();
        shell = new Shell(Display.getCurrent());
        page = new CtrlKeysPreferencePage();
        page.init(PlatformUI.getWorkbench());
        page.createControl(shell);
    }

    @AfterEach
    void close() {
        page.dispose();
        shell.dispose();
        for (char letter : VimCtrlKeys.LETTERS.toCharArray()) {
            store.setToDefault(PreferenceConstants.ctrlKeyToNeovim(letter));
        }
    }

    private List<Button> buttons(int style) {
        List<Button> found = new ArrayList<>();
        collect(page.getControl(), style, found);
        return found;
    }

    private static void collect(Control control, int style, List<Button> found) {
        if (control instanceof Button b && (b.getStyle() & style) != 0) {
            found.add(b);
        }
        if (control instanceof Composite c) {
            for (Control child : c.getChildren()) {
                collect(child, style, found);
            }
        }
    }

    private void press(String label) {
        Button button = buttons(SWT.PUSH).stream().filter(b -> label.equals(b.getText())).findFirst().orElseThrow();
        button.notifyListeners(SWT.Selection, null);
    }

    @Test
    void thereIsOneCheckBoxPerLetter() {
        assertEquals(26, buttons(SWT.CHECK).size());
    }

    @Test
    void defaultsLeaveSWAndZToEclipse() {
        press("Select All");
        page.performDefaults();
        page.performOk();
        for (char letter : VimCtrlKeys.LETTERS.toCharArray()) {
            assertEquals(VimCtrlKeys.toNeovimByDefault(letter), store.getBoolean(PreferenceConstants.ctrlKeyToNeovim(letter)),
                    "letter " + letter);
        }
    }

    @Test
    void deselectAllThenApplyGivesEveryLetterToEclipse() {
        press("Deselect All");
        assertTrue(buttons(SWT.CHECK).stream().noneMatch(Button::getSelection));
        page.performOk();
        for (char letter : VimCtrlKeys.LETTERS.toCharArray()) {
            assertFalse(store.getBoolean(PreferenceConstants.ctrlKeyToNeovim(letter)), "letter " + letter);
        }
    }

    @Test
    void selectAllThenApplyGivesEveryLetterToNeovim() {
        press("Select All");
        page.performOk();
        assertTrue(store.getBoolean(PreferenceConstants.ctrlKeyToNeovim('s')));
        assertTrue(Activator.getDefault().isCtrlKeyToNeovim('w'));
    }
}
