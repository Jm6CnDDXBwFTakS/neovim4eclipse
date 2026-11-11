// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

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

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PlatformUtil;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimCtrlKeys;

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
            store.setToDefault(PreferenceConstants.ctrlKeyInInsert(letter));
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
    void thereAreTwoCheckBoxesPerLetter() {
        assertEquals(52, buttons(SWT.CHECK).size());
    }

    @Test
    void eachRowSaysWhatTheKeyDoesInBothModes() {
        List<String> texts = buttons(SWT.CHECK).stream().map(Button::getText).toList();
        assertTrue(texts.contains("window commands: splits") && texts.contains("delete the word before"), texts.toString());
    }

    @Test
    void defaultsFollowThePlatform() {
        press("Select All Normal");
        press("Select All Insert");
        page.performDefaults();
        page.performOk();
        for (char letter : VimCtrlKeys.LETTERS.toCharArray()) {
            assertEquals(VimCtrlKeys.toNeovimByDefault(letter), store.getBoolean(PreferenceConstants.ctrlKeyToNeovim(letter)),
                    "normal " + letter);
            assertEquals(VimCtrlKeys.inInsertByDefault(letter, PlatformUtil.isMac()),
                    store.getBoolean(PreferenceConstants.ctrlKeyInInsert(letter)), "insert " + letter);
        }
    }

    @Test
    void deselectAllNormalLeavesTheInsertColumn() {
        press("Deselect All Normal");
        page.performOk();
        for (char letter : VimCtrlKeys.LETTERS.toCharArray()) {
            assertFalse(store.getBoolean(PreferenceConstants.ctrlKeyToNeovim(letter)), "normal " + letter);
        }
        assertEquals(VimCtrlKeys.inInsertByDefault('w', PlatformUtil.isMac()), Activator.getDefault().isCtrlKeyInInsert('w'));
    }

    @Test
    void selectAllInsertThenApplyGivesEveryInsertLetterToNeovim() {
        press("Select All Insert");
        page.performOk();
        assertTrue(Activator.getDefault().isCtrlKeyInInsert('v'));
        assertTrue(Activator.getDefault().isCtrlKeyInInsert('s'));
        assertFalse(store.getBoolean(PreferenceConstants.ctrlKeyToNeovim('s')), "the Normal column is untouched");
    }
}
