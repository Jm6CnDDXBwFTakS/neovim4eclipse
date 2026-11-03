// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
                || label.getText().startsWith("No Neovim program at") || label.getText().startsWith("Looking for"));
        field.setText("/no/such/folder/with/a/rather/long/name/so/that/the/note/wraps/onto/several/lines/nvim");
        waitUntil("the note to report the missing program", () -> note.getText().startsWith("No Neovim program at"));
        waitUntil("the help to lie inside its group", () -> {
            Rectangle group = program.getClientArea();
            Rectangle link = help.getBounds();
            return link.height > 0 && link.y + link.height <= group.y + group.height;
        });
    }

    /**
     * Typing a program's path does not run it, since a half-typed path may name another user's
     * file (a pause at {@code /tmp/nvim} while typing {@code /tmp/nvim-0.12/bin/nvim}): the note
     * names it and says its version shows after Apply (file-by-file security review of 0.2.1).
     * The program here is a script that leaves a mark when run.
     */
    @Test
    void aTypedProgramIsNotRun() throws Exception {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "a shell script as the program");
        Path dir = Files.createTempDirectory("neovim4eclipse-typed");
        Path mark = dir.resolve("ran");
        Path program = dir.resolve("nvim");
        Files.writeString(program, "#!/bin/sh\ntouch '" + mark + "'\n");
        Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
        Group group = page.groups()[0];
        Text field = find(group, Text.class);
        Label note = find(group, Label.class, label -> label.getText().startsWith("Found: ")
                || label.getText().startsWith("No ") || label.getText().startsWith("Looking for"));
        field.setText(program.toString());
        waitUntil("the note to name the program", () -> note.getText().startsWith("Program: " + program));
        assertTrue(note.getText().contains(". Its version shows after Apply."), note.getText());
        assertFalse(Files.exists(mark), "the program did not run");
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
                label -> label.getText().startsWith("No Neovim program at")) != null);
        Label note = find(program, Label.class, label -> label.getText().startsWith("No Neovim program at"));
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
