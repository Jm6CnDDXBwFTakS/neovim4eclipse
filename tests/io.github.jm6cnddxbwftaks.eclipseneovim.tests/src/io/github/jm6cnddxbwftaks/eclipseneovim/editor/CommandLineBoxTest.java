// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import static io.github.jm6cnddxbwftaks.eclipseneovim.editor.UiTestSupport.spin;
import static io.github.jm6cnddxbwftaks.eclipseneovim.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.CommandLine.Kind;

/** The {@code :} box keeps its prefix, whatever the platform does with the selection. */
class CommandLineBoxTest {

    private Shell shell;
    private CommandLineBox commandLine;
    private Text box;

    @BeforeEach
    void open() {
        shell = new Shell(Display.getCurrent());
        shell.setSize(400, 300);
        StyledText host = new StyledText(shell, SWT.MULTI);
        host.setBounds(0, 0, 400, 300);
        shell.open();
        commandLine = new CommandLineBox(host, null, null); // no session or editor: nothing is run
        commandLine.open(Kind.EX, false);
        waitUntil("the box to open", 5000, () -> findText(host) != null);
        box = findText(host);
    }

    @AfterEach
    void close() {
        commandLine.dispose();
        shell.dispose();
    }

    private static Text findText(Control control) {
        if (control instanceof Text t) {
            return t;
        }
        if (control instanceof Composite c) {
            for (Control child : c.getChildren()) {
                Text found = findText(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void startsWithThePrefix() {
        assertEquals(":", box.getText());
    }

    @Test
    void typingAfterThePrefixWorks() {
        spin(200); // past the guard against an echo of the opening ':'
        box.insert("w");
        assertEquals(":w", box.getText());
    }

    @Test
    void typingOverASelectionThatIncludesThePrefixKeepsThePrefix() {
        spin(200);
        box.selectAll(); // what macOS does when the field gains focus
        box.insert("w");
        spin(100); // the redirected edit is applied just after the verify listener
        assertEquals(":w", box.getText());
    }

    @Test
    void deletingEverythingLeavesThePrefix() {
        spin(200);
        box.insert("wq");
        box.selectAll();
        box.insert("");
        spin(100);
        assertEquals(":", box.getText());
    }
}
