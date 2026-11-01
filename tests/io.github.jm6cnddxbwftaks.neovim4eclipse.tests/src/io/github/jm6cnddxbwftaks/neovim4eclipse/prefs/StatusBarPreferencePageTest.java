// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Status Bar page refuses numbers out of their range, and the page stays invalid (Eclipse
 * disables OK and Apply) until they are fixed; Restore Defaults brings back the default width.
 * The page has no mode style to choose: the mode has one set of names (util/ModeLabels). The manual tests "Preferences apply at once" and "The preference pages".
 *
 * <p>The page is never applied, so nothing reaches the preference store: the field editors keep
 * their values until OK or Apply ({@link org.eclipse.jface.preference.FieldEditorPreferencePage}),
 * and Restore Defaults only fills them with the defaults. How the bar follows a stored change is
 * {@code StatusBarEndToEndTest}'s.
 */
class StatusBarPreferencePageTest {

    private Shell shell;
    private StatusBarPreferencePage page;

    @BeforeEach
    void open() {
        shell = new Shell(Display.getCurrent());
        shell.setLayout(new FillLayout());
        page = new StatusBarPreferencePage();
        page.init(PlatformUI.getWorkbench());
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

        press(button("Defaults"));

        assertEquals(String.valueOf(PreferenceConstants.STATUS_STATUSLINE_WIDTH_DEFAULT), width.getText());
        assertTrue(page.isValid());
    }

    /**
     * The page's push button whose label contains {@code word}, mnemonic marks ({@code &}) left out:
     * Restore Defaults is made by {@link org.eclipse.jface.preference.PreferencePage} itself, below
     * the field editors.
     */
    private Button button(String word) {
        List<Button> found = all(shell, Button.class,
                b -> (b.getStyle() & SWT.PUSH) != 0 && b.getText().replace("&", "").contains(word));
        assertEquals(1, found.size(), "one button with " + word);
        return found.get(0);
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

    private static void press(Button button) {
        Event click = new Event();
        click.widget = button;
        button.notifyListeners(SWT.Selection, click);
    }

    /** Every control of {@code type} under {@code root} that passes {@code test}, in layout order. */
    private static <T extends Control> List<T> all(Control root, Class<T> type, Predicate<T> test) {
        List<T> found = new ArrayList<>();
        collect(root, type, test, found);
        return found;
    }

    private static <T extends Control> void collect(Control root, Class<T> type, Predicate<T> test, List<T> found) {
        if (type.isInstance(root) && test.test(type.cast(root))) {
            found.add(type.cast(root));
        }
        if (root instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                collect(child, type, test, found);
            }
        }
    }
}
