// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.editor.FailureEvidence;
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
        Menu bar = menuBar();
        MenuItem neovim = neovimMenuItem(bar);
        assertNotNull(neovim, "the Neovim menu in the bar");
        assertEquals('v', mnemonic(neovim.getText()), "the Neovim menu's text: " + neovim.getText());
        List<String> others = new ArrayList<>();
        for (MenuItem item : bar.getItems()) {
            if (item != neovim) {
                others.add(item.getText());
            }
        }
        for (String text : others) {
            assertTrue(mnemonic(text) != 'v', "another menu uses V: " + text + "; the bar: " + others);
        }
    }

    /**
     * Each entry of the Neovim menu has its own underlined letter, so {@code Alt+V} then a letter
     * runs it: v Neovim Mode, A Attach, R Restart, W Welcome, n Preferences (as Eclipse's own
     * Window > Preferences). The entries are made when the menu opens, so the test opens it as
     * SWT would ({@code SWT.Show}).
     */
    @Test
    void eachEntryOfTheMenuHasItsOwnLetter() {
        waitUntil("the Neovim menu is shown", this::neovimMenuShown);
        MenuItem item = neovimMenuItem(menuBar());
        assertNotNull(item, "the Neovim menu in the bar");
        Menu neovim = item.getMenu();
        neovim.notifyListeners(SWT.Show, new Event()); // fills the menu, as opening it does
        Map<String, Character> letters = new LinkedHashMap<>();
        try {
            for (MenuItem entry : neovim.getItems()) {
                if ((entry.getStyle() & SWT.SEPARATOR) == 0) {
                    String label = entry.getText().split("\t")[0]; // a key binding follows a tab
                    letters.put(label.replace("&", ""), mnemonic(label));
                }
            }
        } finally {
            neovim.notifyListeners(SWT.Hide, new Event()); // closed again, also after a failure
        }
        // Get Neovim shows only while the plug-in's own Neovim is missing or old (GetNeovimOffer);
        // getNeovimIsInTheMenuOnlyWhileTheDedicatedNeovimIsMissing tests both cases. Here only its letter.
        Character getNeovim = letters.remove("Get Neovim...");
        if (getNeovim != null) {
            assertEquals('g', getNeovim, "Get Neovim's letter");
        }
        assertEquals(Map.of("Neovim Mode", 'v', "Attach Neovim", 'a', "Restart Neovim...", 'r',
                "Welcome...", 'w', "Report a Problem...", 'p', "Preferences...", 'n'), letters);
    }

    /**
     * Get Neovim is in the menu only while the plug-in's own Neovim is missing or fails its check
     * ({@code GetNeovimOffer}). Both cases on every machine: the tests' data folder is their own,
     * and {@link DedicatedNeovim} puts the dedicated Neovim there or takes it away.
     */
    @Test
    void getNeovimIsInTheMenuOnlyWhileTheDedicatedNeovimIsMissing() {
        waitUntil("the Neovim menu is shown", this::neovimMenuShown);
        DedicatedNeovim.remove();
        waitUntil("Get Neovim in the menu, with no dedicated Neovim", () -> menuLabels().contains("Get Neovim..."));
        DedicatedNeovim.install();
        try {
            waitUntil("no Get Neovim in the menu, with the dedicated Neovim installed",
                    () -> !menuLabels().contains("Get Neovim..."));
        } finally {
            DedicatedNeovim.remove();
        }
    }

    /** The Neovim menu's entries, without mnemonic marks and key bindings, as the menu shows them now. */
    private static List<String> menuLabels() {
        MenuItem item = neovimMenuItem(menuBar());
        assertNotNull(item, "the Neovim menu in the bar");
        Menu neovim = item.getMenu();
        neovim.notifyListeners(SWT.Show, new Event()); // fills the menu, as opening it does
        List<String> labels = new ArrayList<>();
        try {
            for (MenuItem entry : neovim.getItems()) {
                if ((entry.getStyle() & SWT.SEPARATOR) == 0) {
                    labels.add(entry.getText().split("\t")[0].replace("&", ""));
                }
            }
        } finally {
            neovim.notifyListeners(SWT.Hide, new Event());
        }
        return labels;
    }

    /** The active workbench window's menu bar. */
    private static Menu menuBar() {
        return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell().getMenuBar();
    }

    /** The bar's Neovim menu, found by its text without the mnemonic mark, or {@code null}. */
    private static MenuItem neovimMenuItem(Menu bar) {
        for (MenuItem item : bar.getItems()) {
            if ("Neovim".equals(item.getText().replace("&", ""))) {
                return item;
            }
        }
        return null;
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

    /**
     * What decides whether the Neovim menu shows, for a failure message: the preference as the
     * store has it (and its default), what a property tester of the class plugin.xml names answers
     * for it (the menu's {@code visibleWhen} asks Eclipse's own instance, which this does not
     * prove works; an exception there would be in Eclipse's log), whether
     * the plug-in has started, which window is active, and the entries of its menu bar. Example:
     * "showMenu = true (default true); tester: true; plug-in ACTIVE; window 'data - e2e/test.txt';
     * menu bar: File, Edit, Navigate, ..., Help" points away from the preference and the tester.
     */
    private String menuState() {
        try {
            return menuStateOrFail();
        } catch (RuntimeException e) {
            return "(the menu's state not read: " + e + ")"; // never instead of the timeout's own message
        }
    }

    private String menuStateOrFail() {
        StringBuilder state = new StringBuilder();
        state.append("showMenu = ").append(store.getBoolean(PreferenceConstants.SHOW_MENU))
                .append(" (default ").append(store.getDefaultBoolean(PreferenceConstants.SHOW_MENU)).append(')');
        try {
            state.append("; tester: ").append(new NeovimPropertyTester().test(null, "showMenu", new Object[0], "true"));
        } catch (RuntimeException e) {
            state.append("; tester threw ").append(e);
        }
        Bundle bundle = Platform.getBundle(Activator.PLUGIN_ID);
        state.append("; plug-in ").append(bundle == null ? "not found" : bundleState(bundle.getState()));
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) {
            return state.append("; no active workbench window").toString();
        }
        state.append("; window '").append(window.getShell().getText()).append('\'');
        Menu bar = window.getShell().getMenuBar();
        List<String> entries = new ArrayList<>();
        if (bar != null) {
            for (MenuItem item : bar.getItems()) {
                entries.add(item.getText().replace("&", ""));
            }
        }
        return state.append("; menu bar: ").append(bar == null ? "none" : String.join(", ", entries)).toString();
    }

    private static String bundleState(int state) {
        return switch (state) {
            case Bundle.ACTIVE -> "ACTIVE";
            case Bundle.STARTING -> "STARTING";
            case Bundle.RESOLVED -> "RESOLVED (not started)";
            case Bundle.STOPPING -> "STOPPING";
            default -> "state " + state;
        };
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

    /**
     * Runs the event loop until {@code condition} holds: the menu bar is updated asynchronously. A
     * timeout says what decides the menu ({@link #menuState}) and writes what the screen and the
     * threads looked like ({@link FailureEvidence}).
     */
    private void waitUntil(String what, BooleanSupplier condition) {
        Display display = Display.getCurrent();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out after " + TIMEOUT_MS + " ms waiting until " + what + "\n  " + menuState()
                        + FailureEvidence.record(what));
            }
            if (!display.readAndDispatch()) {
                display.timerExec(10, () -> { });
                display.sleep();
            }
        }
    }
}
