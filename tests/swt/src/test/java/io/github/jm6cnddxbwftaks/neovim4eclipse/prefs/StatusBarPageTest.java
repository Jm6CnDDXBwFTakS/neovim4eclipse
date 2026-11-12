// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PageTesting.all;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PageTesting.press;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PageTesting.pushButton;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.jface.preference.PreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Status Bar page ({@link StatusBarPage}) refuses numbers out of their range, and the page stays
 * invalid (Eclipse disables OK and Apply) until they are fixed; Restore Defaults brings back the
 * default width; Apply stores what the page shows. The page has no mode style to choose: the mode
 * has one set of names (util/ModeLabels).
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

    /** Out of range: a width of 5 or 400 (10 to 300 allowed), a share of 95 (10 to 90). */
    @Test
    void numbersOutOfRangeAreRefusedUntilFixed() {
        List<Text> fields = all(page.getControl(), Text.class, t -> true);
        assertEquals(2, fields.size(), "the statusline width, then the share");
        Text width = fields.get(0);
        Text share = fields.get(1);
        assertTrue(page.isValid(), "valid as loaded");

        for (String refused : List.of("5", "400")) {
            enter(width, refused);
            assertFalse(page.isValid(), "a width of " + refused + " is refused");
        }
        enter(width, "120");
        assertTrue(page.isValid(), "a width of 120 is fine");

        enter(share, "95");
        assertFalse(page.isValid(), "a share of 95% is refused");
        enter(share, "40");
        assertTrue(page.isValid(), "fixed again");
    }

    /** Restore Defaults, pressed as a person would, after another width; no mode style is offered. */
    @Test
    void restoreDefaultsBringsBackTheDefaultWidth() {
        assertTrue(all(page.getControl(), Button.class, b -> (b.getStyle() & SWT.RADIO) != 0).isEmpty(),
                "no mode style to choose");
        Text width = all(page.getControl(), Text.class, t -> true).get(0);
        enter(width, "120");

        press(pushButton(shell, "Restore Defaults"));

        assertEquals(String.valueOf(PreferenceConstants.STATUS_STATUSLINE_WIDTH_DEFAULT), width.getText());
        assertTrue(page.isValid());
    }

    /**
     * Until OK or Apply the field editors keep their values to themselves; Apply then writes them to
     * the store, which the plug-in listens to.
     */
    @Test
    void applyStoresTheWidthAndTheParts() {
        Text width = all(page.getControl(), Text.class, t -> true).get(0);
        Button statusline = PageTesting.find(page.getControl(), Button.class, b -> b.getText().startsWith("statusline"));
        enter(width, "120");
        statusline.setSelection(!statusline.getSelection());
        press(statusline);
        assertEquals(PreferenceConstants.STATUS_STATUSLINE_WIDTH_DEFAULT, store.getInt(PreferenceConstants.STATUS_STATUSLINE_WIDTH),
                "nothing stored before Apply");

        press(pushButton(shell, "Apply"));

        assertEquals(120, store.getInt(PreferenceConstants.STATUS_STATUSLINE_WIDTH));
        assertEquals(!PreferenceConstants.STATUS_STATUSLINE_DEFAULT, store.getBoolean(PreferenceConstants.STATUS_STATUSLINE));
    }

    /**
     * Types {@code value} into a number field as a person would: the text, then the key's release,
     * on which a field editor checks the value ({@code StringFieldEditor.VALIDATE_ON_KEY_STROKE});
     * setting the text alone checks nothing.
     */
    private static void enter(Text field, String value) {
        field.setText(value);
        field.notifyListeners(SWT.KeyUp, new Event());
    }
}
