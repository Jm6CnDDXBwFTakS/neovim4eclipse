// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.Command;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.NeovimPreferencePage;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PreferenceConstants;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WelcomeNote;

/**
 * The welcome dialog and its switches, in a running workbench. When it is due and what it says are
 * tested in {@code tests/unit} ({@code WelcomeNoteTest}); this checks the Eclipse glue: the dialog
 * opens with its buttons, the toggle's key binding is found, the command is registered, the
 * preference has its check box, and the UI tests themselves run with the welcome off.
 *
 * <p>The dialogs are opened with {@code setBlockOnOpen(false)}, so the test keeps running while
 * they are up; a blocking dialog would wait for a click that never comes.
 */
class WelcomeNoticeTest {

    private MessageDialog dialog;

    @AfterEach
    void close() {
        if (dialog != null) {
            dialog.close();
        }
    }

    private MessageDialog open(WelcomeNote.Kind kind, String version) {
        Shell shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
        dialog = WelcomeNotice.create(shell, kind, version);
        dialog.setBlockOnOpen(false);
        dialog.open();
        UiTestSupport.spin(200);
        return dialog;
    }

    @Test
    void theUiTestsRunWithTheWelcomeOff() {
        // test-customization.ini, passed with -pluginCustomization; without it every test that starts
        // Neovim would stop at a modal dialog.
        assertFalse(Activator.getDefault().getPreferenceStore().getBoolean(PreferenceConstants.SHOW_WELCOME));
    }

    @Test
    void theWelcomeHasItsButtonsAndNamesTheToggleKey() {
        open(WelcomeNote.Kind.WELCOME, null);
        // Sorted: the on-screen order depends on the platform (GTK puts the default button last).
        assertEquals(List.of("Close", "Documentation", "Open Preferences"), sorted(buttonLabels(dialog.getShell())));
        String key = WelcomeNotice.toggleKey();
        assertNotNull(key, "the toggle command has no key binding");
        String text = labelText(dialog.getShell());
        assertTrue(text.contains(key + ", the Neovim toolbar button"), text);
    }

    @Test
    void theUpdateNoteHasItsButtons() {
        open(WelcomeNote.Kind.UPDATED, "0.2.0.v20261101-1200");
        assertEquals(List.of("Close", "What's New"), sorted(buttonLabels(dialog.getShell())));
        assertTrue(labelText(dialog.getShell()).contains("updated to 0.2.0."));
    }

    @Test
    void closeClosesTheDialog() {
        open(WelcomeNote.Kind.WELCOME, null);
        Button close = button(dialog.getShell(), "Close");
        close.notifyListeners(SWT.Selection, null);
        UiTestSupport.spin(100);
        assertTrue(dialog.getShell() == null || dialog.getShell().isDisposed(), "the dialog is still open");
        dialog = null;
    }

    @Test
    void theWelcomeCommandIsRegisteredWithAHandler() {
        ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        Command welcome = commands.getCommand("io.github.jm6cnddxbwftaks.neovim4eclipse.commands.welcome");
        assertTrue(welcome.isDefined());
        assertTrue(welcome.isHandled());
    }

    @Test
    void theNeovimPageHasACheckboxForIt() {
        Shell shell = new Shell(Display.getCurrent());
        NeovimPreferencePage page = new NeovimPreferencePage();
        try {
            page.init(PlatformUI.getWorkbench());
            page.createControl(shell);
            List<String> labels = buttonLabels(page.getControl());
            assertTrue(labels.stream().anyMatch(l -> l.startsWith("Show a welcome after installing")),
                    "checkboxes: " + labels);
        } finally {
            page.dispose();
            shell.dispose();
        }
    }

    // ---- helpers ----

    private static List<String> sorted(List<String> labels) {
        return labels.stream().sorted().toList();
    }

    private static List<String> buttonLabels(Control root) {
        List<String> labels = new ArrayList<>();
        for (Button b : find(root, Button.class)) {
            labels.add(b.getText());
        }
        return labels;
    }

    private static Button button(Control root, String text) {
        return find(root, Button.class).stream().filter(b -> text.equals(b.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no button " + text));
    }

    private static String labelText(Control root) {
        StringBuilder text = new StringBuilder();
        for (Label l : find(root, Label.class)) {
            text.append(l.getText()).append('\n');
        }
        return text.toString();
    }

    private static <T extends Control> List<T> find(Control root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        return found;
    }

    private static <T extends Control> void collect(Control control, Class<T> type, List<T> found) {
        if (type.isInstance(control)) {
            found.add(type.cast(control));
        }
        if (control instanceof Composite c) {
            for (Control child : c.getChildren()) {
                collect(child, type, found);
            }
        }
    }
}
