// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PageTesting.all;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PageTesting.pushButton;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.jface.preference.PreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PlatformUtil;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimCtrlKeys;

/**
 * The Ctrl Keys page ({@link CtrlKeysPage}): its buttons, Restore Defaults, and what Apply stores,
 * on a store of its own with the plug-in's defaults ({@link PageTesting#store}). The Activator reads
 * the same keys ({@link PreferenceConstants#ctrlKeyInsert}), so a value stored here is what the
 * editor would get.
 */
class CtrlKeysPageTest {

    private Shell shell;
    private PreferenceStore store;
    private CtrlKeysPage page;

    @BeforeEach
    void open() {
        shell = new Shell(Display.getDefault());
        store = PageTesting.store();
        page = new CtrlKeysPage(store);
        page.createControl(shell);
    }

    @AfterEach
    void close() {
        page.dispose();
        shell.dispose();
    }

    private List<Button> boxes() {
        return all(page.getControl(), Button.class, b -> (b.getStyle() & SWT.CHECK) != 0);
    }

    private void press(String label) {
        PageTesting.press(pushButton(page.getControl(), label));
    }

    @Test
    void thereAreTwoCheckBoxesPerLetter() {
        assertEquals(2 * VimCtrlKeys.LETTERS.length(), boxes().size());
    }

    @Test
    void eachRowSaysWhatTheKeyDoesInBothModes() {
        List<String> texts = boxes().stream().map(Button::getText).toList();
        assertTrue(texts.contains("window commands: splits") && texts.contains("delete the word before"), texts.toString());
    }

    @Test
    void defaultsFollowThePlatform() {
        press("Select All Normal");
        press("Select All Insert");
        page.performDefaults();
        page.performOk();
        for (char letter : VimCtrlKeys.LETTERS.toCharArray()) {
            assertEquals(VimCtrlKeys.toNeovimByDefault(letter), store.getBoolean(PreferenceConstants.ctrlKeyNormal(letter)),
                    "normal " + letter);
            assertEquals(VimCtrlKeys.inInsertByDefault(letter, PlatformUtil.isMac()),
                    store.getBoolean(PreferenceConstants.ctrlKeyInsert(letter)), "insert " + letter);
        }
    }

    @Test
    void deselectAllNormalLeavesTheInsertColumn() {
        press("Deselect All Normal");
        page.performOk();
        for (char letter : VimCtrlKeys.LETTERS.toCharArray()) {
            assertFalse(store.getBoolean(PreferenceConstants.ctrlKeyNormal(letter)), "normal " + letter);
        }
        assertEquals(VimCtrlKeys.inInsertByDefault('w', PlatformUtil.isMac()),
                store.getBoolean(PreferenceConstants.ctrlKeyInsert('w')));
    }

    @Test
    void selectAllInsertThenApplyGivesEveryInsertLetterToNeovim() {
        press("Select All Insert");
        page.performOk();
        assertTrue(store.getBoolean(PreferenceConstants.ctrlKeyInsert('v')));
        assertTrue(store.getBoolean(PreferenceConstants.ctrlKeyInsert('s')));
        assertFalse(store.getBoolean(PreferenceConstants.ctrlKeyNormal('s')), "the Normal column is untouched");
    }

    /** The page shows what is stored, not the defaults, when it opens. */
    @Test
    void thePageOpensWithTheStoredChoice() {
        page.dispose();
        store.setValue(PreferenceConstants.ctrlKeyNormal('s'), !VimCtrlKeys.toNeovimByDefault('s'));
        page = new CtrlKeysPage(store);
        page.createControl(shell);
        int s = VimCtrlKeys.LETTERS.indexOf('s');
        assertEquals(!VimCtrlKeys.toNeovimByDefault('s'), boxes().get(2 * s).getSelection(), "Ctrl+S, Normal");
    }
}
