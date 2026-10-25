// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.NeovimPreferencePage;

/**
 * The Neovim menu's Preferences dialog: the main page's fields (they once ended up in a second,
 * hidden window), and the expanded sub-pages.
 */
class OpenPreferencesHandlerTest {

    private PreferenceDialog dialog;

    @BeforeEach
    void open() {
        dialog = OpenPreferencesHandler.create(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell());
        dialog.setBlockOnOpen(false);
        dialog.open();
        UiTestSupport.spin(300);
    }

    @AfterEach
    void close() {
        dialog.close();
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

    @Test
    void showsTheNeovimPageWithTheLocationField() {
        assertTrue(dialog.getSelectedPage() instanceof NeovimPreferencePage, String.valueOf(dialog.getSelectedPage()));
        Control page = ((NeovimPreferencePage) dialog.getSelectedPage()).getControl();
        Label label = find(page, Label.class).stream()
                .filter(l -> l.getText().startsWith("Neovim folder or program")).findFirst().orElse(null);
        assertNotNull(label, "no 'Neovim folder or program' label");
        assertTrue(find(page, Button.class).stream().anyMatch(b -> b.getText().equals("Browse...")), "no Browse... button");
        // The note under the field names what the field finds (NvimProbe), after a short delay.
        UiTestSupport.waitUntil("the note saying which Neovim was found", 10_000, () -> find(page, Label.class)
                .stream().anyMatch(l -> l.getText().startsWith("Found: ") || l.getText().startsWith("No Neovim found")));
        Text field = find(page, Text.class).stream().findFirst().orElse(null);
        assertNotNull(field, "no text field");
        assertTrue(field.isVisible(), "field hidden (its window is not the dialog's)");
        assertTrue(field.getSize().x > 50 && field.getSize().y > 5, "field size " + field.getSize());
        assertTrue(field.getShell() == dialog.getShell(), "the page is shown in another window");
    }

    @Test
    void neovimNodeIsExpandedWithItsSubPages() {
        TreeItem neovim = null;
        for (TreeItem item : dialog.getTreeViewer().getTree().getItems()) {
            if ("Neovim".equals(item.getText())) {
                neovim = item;
            }
        }
        assertNotNull(neovim);
        assertTrue(neovim.getExpanded());
        List<String> children = new ArrayList<>();
        for (TreeItem child : neovim.getItems()) {
            children.add(child.getText());
        }
        assertEquals(List.of("Ctrl Keys", "Status Bar"), children.stream().sorted().toList());
    }
}
