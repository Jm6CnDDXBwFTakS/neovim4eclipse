// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.NeovimPreferencePage;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimVersion;

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

    /**
     * The page shows which Neovim runs, with nothing to type: since 0.2.1 the program is the startup
     * option {@code -Dneovim4eclipse.nvim} (util/NeovimOption), so there is no field and no Browse.
     */
    @Test
    void showsTheNeovimPageWithWhichNeovimRuns() {
        assertTrue(dialog.getSelectedPage() instanceof NeovimPreferencePage, String.valueOf(dialog.getSelectedPage()));
        Control page = ((NeovimPreferencePage) dialog.getSelectedPage()).getControl();
        // The one field is the help's command to copy, read-only: there is none to type a program into.
        assertTrue(find(page, Text.class).stream().allMatch(t -> (t.getStyle() & SWT.READ_ONLY) != 0),
                "an editable text field, but the program is a startup option now");
        assertTrue(find(page, Button.class).stream().noneMatch(b -> b.getText().equals("Browse...")), "a Browse... button");
        // The note names which Neovim runs (NvimProbe), once the probe has answered.
        UiTestSupport.waitUntil("the note saying which Neovim was found", EndToEndTestBase.TIMEOUT_MS, () -> note(page) != null);
        // Get Neovim is offered on the page, with the version it installs (GetNeovim): as a button, or,
        // when the note finds the dedicated Neovim current, as the note's Reinstall link instead.
        Button getNeovim = find(page, Button.class).stream()
                .filter(b -> b.getText().equals("Get Neovim " + NeovimVersion.MINIMUM + "...")).findFirst().orElse(null);
        assertNotNull(getNeovim, "no Get Neovim button");
        Link reinstallLink = find(page, Link.class).stream()
                .filter(l -> l.getText().equals("<a>Reinstall...</a>")).findFirst().orElse(null);
        assertNotNull(reinstallLink, "no Reinstall link (shown only while the dedicated Neovim is current)");
        boolean reinstall = reinstallLink.isVisible();
        assertEquals(!reinstall, getNeovim.isEnabled() && getNeovim.isVisible(),
                "the button is usable unless Reinstall is offered: " + note(page).getText());
        Label note = note(page);
        assertTrue(note.isVisible(), "note hidden (its window is not the dialog's)");
        assertTrue(note.getShell() == dialog.getShell(), "the page is shown in another window");
    }

    @Test
    void underTheNoteTheHelpSaysHowToRunAnotherNeovimAndLinksToHowNeovimIsFound() {
        NeovimPreferencePage neovimPage = (NeovimPreferencePage) dialog.getSelectedPage();
        assertEquals("Configure Neovim for Eclipse integration.", neovimPage.getDescription());
        Control page = neovimPage.getControl();
        Link help = find(page, Link.class).stream()
                .filter(l -> l.getText().equals(NeovimPreferencePage.NEOVIM_HELP)).findFirst().orElse(null);
        assertNotNull(help, "no help with a link to how Neovim is found");
        assertTrue(help.isVisible(), "help hidden");
        UiTestSupport.waitUntil("the note saying which Neovim was found", EndToEndTestBase.TIMEOUT_MS, () -> note(page) != null);
        Label note = note(page);
        assertTrue(note.getParent() == help.getParent() && note.getBounds().y < help.getBounds().y,
                "the note (which Neovim runs) comes before the help (how to run another): note " + note.getBounds()
                        + ", help " + help.getBounds());
    }

    /** The note in the Neovim group (a Label), once it says which Neovim runs; {@code null} before. */
    private static Label note(Control page) {
        return find(page, Label.class).stream()
                .filter(l -> l.getText().startsWith("Found: ") || l.getText().startsWith("No dedicated Neovim")
                        || l.getText().startsWith("The dedicated Neovim's files")
                        || l.getText().contains("-Dneovim4eclipse.nvim"))
                .findFirst().orElse(null);
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
