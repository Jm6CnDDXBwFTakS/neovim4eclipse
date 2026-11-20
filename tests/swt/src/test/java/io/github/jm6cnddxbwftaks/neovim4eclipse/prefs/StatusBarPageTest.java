// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PageTesting.all;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PageTesting.press;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PageTesting.pushButton;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.jface.preference.PreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Status Bar page ({@link StatusBarPage}) shows one check box, the mode: Neovim's status line
 * left the status bar for a row under each editor's text ({@code editor/StatusLineRow}), and its
 * width settings with it. Restore Defaults brings the default back; Apply stores what the page shows.
 *
 * <p>The page works on a store of its own ({@link PageTesting#store}). How the bar follows a stored
 * change is {@code StatusBarEndToEndTest}'s, in the UI tests.
 */
class StatusBarPageTest {

    private Shell shell;
    private PreferenceStore store;
    private StatusBarPage page;

    @BeforeEach
    void open() {
        shell = new Shell(Display.getDefault());
        shell.setLayout(new FillLayout());
        store = PageTesting.store();
        page = new StatusBarPage(store);
        page.createControl(shell);
        shell.setSize(600, 500);
        shell.layout(true, true);
    }

    @AfterEach
    void close() {
        page.dispose();
        shell.dispose();
    }

    /** No number fields and no style to choose: the mode check box alone. */
    @Test
    void theModeIsTheOnlySetting() {
        assertTrue(all(page.getControl(), Text.class, t -> true).isEmpty(), "no width fields");
        assertTrue(all(page.getControl(), Button.class, b -> (b.getStyle() & SWT.RADIO) != 0).isEmpty(),
                "no mode style to choose");
        List<Button> boxes = all(page.getControl(), Button.class, b -> (b.getStyle() & SWT.CHECK) != 0);
        assertEquals(1, boxes.size(), "one check box: " + boxes);
        assertTrue(boxes.get(0).getText().startsWith("mode"));
    }

    /**
     * Until OK or Apply the field editor keeps its value to itself; Apply then writes it to the
     * store, which the plug-in listens to; Restore Defaults brings back the default.
     */
    @Test
    void applyStoresTheModeAndRestoreDefaultsBringsItBack() {
        Button mode = PageTesting.find(page.getControl(), Button.class, b -> b.getText().startsWith("mode"));
        mode.setSelection(!mode.getSelection());
        press(mode);
        assertEquals(PreferenceConstants.STATUS_MODE_DEFAULT, store.getBoolean(PreferenceConstants.STATUS_MODE),
                "nothing stored before Apply");

        press(pushButton(shell, "Apply"));
        assertEquals(!PreferenceConstants.STATUS_MODE_DEFAULT, store.getBoolean(PreferenceConstants.STATUS_MODE));

        press(pushButton(shell, "Restore Defaults"));
        assertEquals(PreferenceConstants.STATUS_MODE_DEFAULT, mode.getSelection());
        assertTrue(page.isValid());
    }
}
