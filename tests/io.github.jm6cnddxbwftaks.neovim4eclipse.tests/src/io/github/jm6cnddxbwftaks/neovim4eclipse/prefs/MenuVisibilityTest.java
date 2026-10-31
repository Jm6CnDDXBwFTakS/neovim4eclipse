// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;

/**
 * The Neovim menu in the menu bar: its mnemonic, and the "Show Neovim menu in the menu bar"
 * preference, which the page offers and which adds or removes the menu while Eclipse runs (through
 * {@code visibleWhen} in {@code plugin.xml}).
 */
class MenuVisibilityTest {

    private static final long TIMEOUT_MS = TestWait.millis();

    private final IPreferenceStore store = Activator.getDefault().getPreferenceStore();

    @AfterEach
    void restore() {
        store.setToDefault(PreferenceConstants.SHOW_MENU);
    }

    @Test
    void theMenuIsShownByDefaultAndFollowsThePreference() {
        waitUntil("the Neovim menu is shown by default", this::neovimMenuShown);

        store.setValue(PreferenceConstants.SHOW_MENU, false);
        waitUntil("the Neovim menu is hidden", () -> !neovimMenuShown());

        store.setValue(PreferenceConstants.SHOW_MENU, true);
        waitUntil("the Neovim menu is back", this::neovimMenuShown);
    }

    /**
     * A menu's mnemonic is the letter after {@code &} in its text, underlined; Alt with it opens the
     * menu (Linux, Windows; macOS has none). With two menus on one letter, that key only moves
     * between them, so the Neovim menu takes V ({@code Neo&vim}), which no other menu of the bar
     * uses. Before, it took N, which is Navigate's.
     */
    @Test
    void theMenuOpensWithAltVALetterNoOtherMenuUses() {
        waitUntil("the Neovim menu is shown", this::neovimMenuShown);
        Menu bar = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell().getMenuBar();
        List<String> others = new ArrayList<>();
        for (MenuItem item : bar.getItems()) {
            String text = item.getText();
            if ("Neovim".equals(text.replace("&", ""))) {
                assertEquals('v', mnemonic(text), "the Neovim menu's text: " + text);
            } else {
                others.add(text);
            }
        }
        for (String text : others) {
            assertTrue(mnemonic(text) != 'v', "another menu uses V: " + text + "; the bar: " + others);
        }
    }

    /**
     * The mnemonic of a menu's text, in lower case: the character after the first single {@code &}
     * ({@code &&} is a literal ampersand). Example: {@code "Se&arch"} gives {@code 'a'}; no
     * mnemonic gives {@code 0}.
     */
    private static char mnemonic(String text) {
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.charAt(i) == '&') {
                if (text.charAt(i + 1) != '&') {
                    return Character.toLowerCase(text.charAt(i + 1));
                }
                i++; // skip the second & of "&&"
            }
        }
        return 0;
    }

    @Test
    void thePageHasACheckboxForIt() {
        Shell shell = new Shell(Display.getCurrent());
        NeovimPreferencePage page = new NeovimPreferencePage();
        try {
            page.init(PlatformUI.getWorkbench());
            page.createControl(shell);
            List<String> labels = new ArrayList<>();
            collectCheckboxLabels(page.getControl(), labels);
            assertTrue(labels.contains("Show Neovim menu in the menu bar"), "checkboxes: " + labels);
        } finally {
            page.dispose();
            shell.dispose();
        }
    }

    /** Whether the workbench window's menu bar has a "Neovim" entry (SWT drops hidden ones). */
    private boolean neovimMenuShown() {
        Menu bar = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell().getMenuBar();
        for (MenuItem item : bar.getItems()) {
            if ("Neovim".equals(item.getText().replace("&", ""))) {
                return true;
            }
        }
        return false;
    }

    private static void collectCheckboxLabels(Control control, List<String> labels) {
        if (control instanceof Button b && (b.getStyle() & SWT.CHECK) != 0) {
            labels.add(b.getText());
        }
        if (control instanceof Composite c) {
            for (Control child : c.getChildren()) {
                collectCheckboxLabels(child, labels);
            }
        }
    }

    /** Runs the event loop until {@code condition} holds: the menu bar is updated asynchronously. */
    private static void waitUntil(String what, BooleanSupplier condition) {
        Display display = Display.getCurrent();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out after " + TIMEOUT_MS + " ms waiting until " + what);
            }
            if (!display.readAndDispatch()) {
                display.timerExec(10, () -> { });
                display.sleep();
            }
        }
    }
}
