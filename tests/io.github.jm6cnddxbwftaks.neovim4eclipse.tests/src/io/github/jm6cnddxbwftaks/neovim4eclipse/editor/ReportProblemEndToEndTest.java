// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs.ProblemReportDialog;

/**
 * <i>Neovim → Report a Problem…</i> in a real Eclipse with a running Neovim: the command opens the
 * dialog, and its report has what the test Eclipse is (the running Neovim's version, the active
 * editor's kind) and nothing of the file (not its name, not the home folder).
 */
class ReportProblemEndToEndTest extends EndToEndTestBase {

    @Test
    void theCommandShowsAReportOfThisEclipseWithNothingOfTheFile() throws Exception {
        runCommand("io.github.jm6cnddxbwftaks.neovim4eclipse.commands.reportProblem");
        Shell[] dialog = new Shell[1];
        waitUntil("the Report a Problem dialog", TIMEOUT_MS, () -> {
            for (Shell shell : widget.getDisplay().getShells()) {
                if (shell.isVisible() && ProblemReportDialog.TITLE.equals(shell.getText())) {
                    dialog[0] = shell;
                    return true;
                }
            }
            return false;
        });
        try {
            Text field = textIn(dialog[0]);
            assertNotNull(field, "the report's text field");
            String report = field.getText();
            assertTrue(report.startsWith("### Neovim for Eclipse problem report\n"), report);
            assertTrue(report.contains("- Running: Neovim " + session.neovimVersion() + "\n"), report);
            assertTrue(report.contains("- Active editor: a .txt file in the "), report);
            assertTrue(report.contains(", Neovim mode on\n"), report);
            assertFalse(report.contains("test.txt"), "no file name: " + report);
            String home = System.getProperty("user.home");
            assertFalse(home.length() > 1 && report.contains(home), "no home folder: " + report);
        } finally {
            dialog[0].close();
        }
    }

    /** The first text field under {@code parent}: the report. */
    private static Text textIn(Composite parent) {
        for (Control child : parent.getChildren()) {
            if (child instanceof Text text) {
                return text;
            }
            if (child instanceof Composite composite) {
                Text found = textIn(composite);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
