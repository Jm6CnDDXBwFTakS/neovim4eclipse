// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.LayoutCheck;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ProblemReport;

/**
 * The problem report's dialog: it shows the whole report, read-only, and fits on this platform's
 * fonts; Copy copies it and stays open; Copy and Open Issues copies it, closes and opens the issues.
 */
class ProblemReportDialogTest {

    private static final String REPORT = new ProblemReport("0.2.16", "4.41.0 (Eclipse IDE for Java Developers)",
            "21.0.5 (Eclipse Adoptium)", "Linux 6.12 aarch64, SWT gtk",
            "Found: dedicated Neovim 0.12.6, checked, /home/u/.local/share/neovim4eclipse-plugin/neovim/la/bin/nvim",
            "0.12.6", false, false, Map.of(), List.of(), "a .java file in the Java Editor, Neovim mode on",
            List.of("14:03:12 WARNING TextRepairs: Neovim's copy of a .java file in the Java Editor differed")).text("/home/u");

    private static ProblemReportDialog open(Runnable openIssues) {
        Display display = Display.getDefault(); // this thread becomes the UI thread, as JFace wants
        ProblemReportDialog dialog = new ProblemReportDialog(null, REPORT, openIssues);
        dialog.setBlockOnOpen(false);
        dialog.open();
        while (display.readAndDispatch()) {
            // let the layout settle
        }
        return dialog;
    }

    @Test
    void theDialogShowsTheWholeReportReadOnlyAndFits() {
        ProblemReportDialog dialog = open(() -> { });
        try {
            assertEquals(REPORT, dialog.reportText().getText());
            assertTrue((dialog.reportText().getStyle() & SWT.READ_ONLY) != 0, "the report is not edited here");
            assertEquals(List.of(), LayoutCheck.problems(dialog.getShell()));
        } finally {
            dialog.close();
        }
    }

    @Test
    void copyPutsTheReportOnTheClipboardAndStaysOpen() {
        AtomicInteger opened = new AtomicInteger();
        ProblemReportDialog dialog = open(opened::incrementAndGet);
        try {
            dialog.buttonPressed(ProblemReportDialog.COPY_ID);
            assertEquals(REPORT, clipboardText());
            assertFalse(dialog.getShell() == null || dialog.getShell().isDisposed(), "still open");
            assertEquals(0, opened.get(), "the issues are not opened");
        } finally {
            dialog.close();
        }
    }

    @Test
    void copyAndOpenIssuesCopiesClosesAndOpens() {
        AtomicInteger opened = new AtomicInteger();
        ProblemReportDialog dialog = open(opened::incrementAndGet);
        dialog.buttonPressed(ProblemReportDialog.COPY_AND_OPEN_ID);
        assertEquals(REPORT, clipboardText());
        assertNull(dialog.getShell(), "closed");
        assertEquals(1, opened.get());
    }

    private static String clipboardText() {
        Clipboard clipboard = new Clipboard(Display.getDefault());
        try {
            return (String) clipboard.getContents(TextTransfer.getInstance());
        } finally {
            clipboard.dispose();
        }
    }
}
