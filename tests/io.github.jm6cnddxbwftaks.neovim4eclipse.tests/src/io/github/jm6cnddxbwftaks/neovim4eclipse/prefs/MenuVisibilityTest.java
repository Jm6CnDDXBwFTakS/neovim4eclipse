// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

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
 * The "Show Neovim menu in the menu bar" preference: the page offers it, and changing it adds or
 * removes the Neovim menu while Eclipse runs (through {@code visibleWhen} in {@code plugin.xml}).
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
