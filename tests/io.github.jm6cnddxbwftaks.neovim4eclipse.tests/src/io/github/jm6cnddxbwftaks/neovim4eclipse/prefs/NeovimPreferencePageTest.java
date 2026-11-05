// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
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
 * The Neovim page's two group boxes: which Neovim runs (the note, Get Neovim and the help) in the
 * first, the check boxes in the second, and the first growing when the note wraps to more lines
 * (before, the help was cut off: the note laid out only its own group, which kept its old height).
 * Since 0.2.1 the first group has no field: the program is the startup option
 * {@code -Dneovim4eclipse.nvim} (util/NeovimOption), so a test of another value builds the section
 * itself with that value ({@link NeovimStatusSection}).
 */
class NeovimPreferencePageTest {

    /**
     * A path is absolute on Windows only with a drive letter: {@code /no/such/nvim} would get the
     * note "-Dneovim4eclipse.nvim must name the absolute path...", not "No Neovim program at".
     */
    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

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
    void whichNeovimRunsAndTheCheckBoxesAreInTwoGroups() {
        Group[] groups = page.groups();
        assertEquals("Neovim", groups[0].getText());
        assertEquals("Workbench", groups[1].getText());
        assertTrue(find(groups[0], Label.class) != null && find(groups[0], Link.class,
                link -> link.getText().equals(NeovimPreferencePage.NEOVIM_HELP)) != null,
                "the note and the help in the first group");
        assertNull(find(groups[0], Text.class), "no field: the program is a startup option");
        assertTrue(groups[1].getBounds().y >= groups[0].getBounds().y + groups[0].getBounds().height,
                "the second group below the first");
    }

    /** A note that wraps to several lines grows its group, so the help under it stays whole. */
    @Test
    void aLongNoteGrowsTheGroupAndKeepsTheHelpVisible() {
        String path = "/no/such/folder/with/a/rather/long/name/so/that/the/note/wraps/onto/several/lines/nvim";
        Group group = sectionFor(WINDOWS ? "C:" + path.replace('/', '\\') + ".exe" : path);
        // The help is a Link (to the README); the note a Label, as it shows paths (NeovimStatusSection).
        Link help = find(group, Link.class, link -> link.getText().equals(NeovimPreferencePage.NEOVIM_HELP));
        waitUntil("the note to report the missing program", () -> find(group, Label.class,
                label -> label.getText().startsWith("No Neovim program at")) != null);
        waitUntil("the help to lie inside its group", () -> {
            Rectangle area = group.getClientArea();
            Rectangle link = help.getBounds();
            return link.height > 0 && link.y + link.height <= area.y + area.height;
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
                NeovimStatusSection.labelText(new NvimProbe.Note(found + unused, false, true)));
    }

    /**
     * A folder named with {@code <a>...</a>} shows as text: the note is a Label, so the name cannot
     * become a link (in a Link it would, and its click would open Get Neovim).
     */
    @Test
    void aFolderNameWithLinkMarkupStaysText() {
        Group group = sectionFor(WINDOWS ? "C:\\no\\<a>such</a>\\nvim.exe" : "/no/<a>such</a>/nvim");
        waitUntil("the note to report the missing program", () -> find(group, Label.class,
                label -> label.getText().startsWith("No Neovim program at")) != null);
        Label note = find(group, Label.class, label -> label.getText().startsWith("No Neovim program at"));
        assertTrue(note.getText().contains("<a>such</a>"), note.getText());
        assertTrue(find(group, Link.class, link -> link.getText().contains("such")) == null,
                "no Link shows the path");
    }

    /**
     * A Neovim group as the page lays it out, in its own window, for the option's value
     * {@code configured} instead of this test Eclipse's.
     */
    private Group sectionFor(String configured) {
        Shell window = new Shell(shell);
        window.setLayout(new GridLayout());
        Group group = new Group(window, SWT.NONE);
        group.setText("Neovim");
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        group.setLayout(new GridLayout());
        new NeovimStatusSection(group, () -> configured);
        window.setSize(600, 500);
        window.layout(true, true);
        return group;
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
