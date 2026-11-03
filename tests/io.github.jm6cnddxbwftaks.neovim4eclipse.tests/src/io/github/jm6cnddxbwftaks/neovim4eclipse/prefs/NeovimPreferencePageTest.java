// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimProbe;

/**
 * The Neovim page's two group boxes: the field, its note and its help in the first, the check boxes
 * in the second, and the first growing when the note wraps to more lines (before, the help was cut
 * off: the note laid out only its own group, which kept its old height).
 */
class NeovimPreferencePageTest {

    private Shell shell;
    private NeovimPreferencePage page;

    @BeforeEach
    void open() {
        shell = new Shell(Display.getCurrent());
        shell.setLayout(new FillLayout());
        page = new NeovimPreferencePage();
        page.init(PlatformUI.getWorkbench());
        page.createControl(shell);
        shell.setSize(600, 500); // narrow enough that a long note wraps
        shell.layout(true, true);
    }

    @AfterEach
    void close() {
        page.dispose();
        shell.dispose();
    }

    @Test
    void theFieldAndTheCheckBoxesAreInTwoGroups() {
        Group[] groups = page.groups();
        assertEquals("Neovim program", groups[0].getText());
        assertEquals("Workbench", groups[1].getText());
        assertTrue(find(groups[0], Text.class) != null && find(groups[0], Link.class) != null,
                "the field and its help in the first group");
        assertTrue(groups[1].getBounds().y >= groups[0].getBounds().y + groups[0].getBounds().height,
                "the second group below the first");
    }

    /** A note that wraps to several lines grows its group, so the help under it stays whole. */
    @Test
    void aLongNoteGrowsTheGroupAndKeepsTheHelpVisible() {
        Group program = page.groups()[0];
        Text field = find(program, Text.class);
        // The help is a Link (to the README); the note a Label, as it shows paths (NeovimLocationFieldEditor).
        Link help = find(program, Link.class, link -> link.getText().equals(NeovimPreferencePage.NEOVIM_FIELD_HELP));
        Label note = find(program, Label.class, label -> label.getText().startsWith("Found: ")
                || label.getText().startsWith("No Neovim found") || label.getText().startsWith("Looking for"));
        field.setText("/no/such/folder/with/a/rather/long/name/so/that/the/note/wraps/onto/several/lines/nvim");
        waitUntil("the note to report the missing program", () -> note.getText().startsWith("No Neovim found"));
        waitUntil("the help to lie inside its group", () -> {
            Rectangle group = program.getClientArea();
            Rectangle link = help.getBounds();
            return link.height > 0 && link.y + link.height <= group.y + group.height;
        });
    }

    /**
     * The note's Label text: a single {@code &} would be a mnemonic marker, so it is doubled; nothing
     * else changes, and the Reinstall link is not in it (a control of its own).
     */
    @Test
    void theNoteTextOnlyDoublesAmpersands() {
        String found = "Found: dedicated Neovim 0.12.6, /R&D/la/bin/nvim";
        String unused = "\nNot used by the plug-in, you can delete:\n  /R&D/<a>current</a>";
        assertEquals("Found: dedicated Neovim 0.12.6, /R&&D/la/bin/nvim\n"
                + "Not used by the plug-in, you can delete:\n  /R&&D/<a>current</a>",
                NeovimLocationFieldEditor.labelText(new NvimProbe.Note(found + unused, false, true)));
    }

    /**
     * A folder named with {@code <a>...</a>} shows as text: the note is a Label, so the name cannot
     * become a link (in a Link it would, and its click would open Get Neovim).
     */
    @Test
    void aFolderNameWithLinkMarkupStaysText() {
        Group program = page.groups()[0];
        Text field = find(program, Text.class);
        field.setText("/no/<a>such</a>/nvim");
        waitUntil("the note to report the missing program", () -> find(program, Label.class,
                label -> label.getText().startsWith("No Neovim found")) != null);
        Label note = find(program, Label.class, label -> label.getText().startsWith("No Neovim found"));
        assertTrue(note.getText().contains("<a>such</a>"), note.getText());
        assertTrue(find(program, Link.class, link -> link.getText().contains("such")) == null,
                "no Link shows the path");
    }

    /** Waits for {@code condition} while the display runs (the note's probe answers through it). */
    private static void waitUntil(String what, BooleanSupplier condition) {
        Display display = Display.getCurrent();
        long deadline = System.currentTimeMillis() + TestWait.millis();
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            if (!display.readAndDispatch()) {
                display.timerExec(20, () -> { }); // wake up again soon
                display.sleep();
            }
        }
    }

    private static <T extends Control> T find(Control root, Class<T> type) {
        return find(root, type, t -> true);
    }

    private static <T extends Control> T find(Control root, Class<T> type, Predicate<T> test) {
        if (type.isInstance(root) && test.test(type.cast(root))) {
            return type.cast(root);
        }
        if (root instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                T found = find(child, type, test);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
