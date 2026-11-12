// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * <i>Neovim → Report a Problem…</i>: the problem report ({@code util/ProblemReport}) to read and
 * copy into a GitHub issue. The text is shown whole, read-only, so that the user sees exactly what
 * would be shared before sharing it.
 *
 * <p>Buttons: <i>Copy and Open Issues</i> copies the report and opens the issue chooser in the
 * browser ({@code openIssues}, given by the caller: this package has no workbench), then closes;
 * <i>Copy</i> copies and stays open; <i>Close</i>, the default, closes. The report never goes into
 * the page's address: an address is logged and kept in a browser's history, a pasted text is not.
 * Example: {@code new ProblemReportDialog(shell, report, () -> browser.open(issues)).open()}.
 */
public class ProblemReportDialog extends MessageDialog {

    /** The dialog's title. */
    public static final String TITLE = "Report a Problem";
    /** What the dialog says above the report. */
    public static final String MESSAGE = "To report a problem, copy this into a GitHub issue, with the steps that"
            + " led to it. It holds no text of your files and no file names, and your home folder is written as ~;"
            + " read it before you share it, since a warning may still name a file.";
    /** The buttons, left to right; the last is the default. */
    static final String COPY_AND_OPEN = "Copy and Open &Issues";
    static final String COPY = "&Copy";
    static final String CLOSE = "Close";
    /** Index of each button, as {@link #buttonPressed} gets it. */
    static final int COPY_AND_OPEN_ID = 0;
    static final int COPY_ID = 1;

    /** How much of the report shows without scrolling: about one line of an issue's width. */
    private static final int COLUMNS = 90;
    private static final int ROWS = 18;

    private final String report;
    private final Runnable openIssues;
    private Text text;

    public ProblemReportDialog(Shell shell, String report, Runnable openIssues) {
        super(shell, TITLE, null, MESSAGE, MessageDialog.INFORMATION, 2, COPY_AND_OPEN, COPY, CLOSE);
        this.report = report;
        this.openIssues = openIssues;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected Control createCustomArea(Composite parent) {
        text = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL); // wrapped: a long warning reads whole
        text.setFont(JFaceResources.getTextFont()); // fixed width, as the issue shows it
        text.setText(report);
        Point size = charSize(text);
        GridDataFactory.fillDefaults().grab(true, true).span(2, 1)
                .hint(size.x * COLUMNS, size.y * ROWS).applyTo(text);
        return text;
    }

    /** The width of an average character and the height of a line in {@code control}'s font. */
    private static Point charSize(Control control) {
        GC gc = new GC(control);
        try {
            gc.setFont(control.getFont());
            return new Point((int) Math.ceil(gc.getFontMetrics().getAverageCharacterWidth()), gc.getFontMetrics().getHeight());
        } finally {
            gc.dispose();
        }
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == COPY_ID) {
            copy();
            return; // stays open: the user may still want to read or open the issues
        }
        if (buttonId == COPY_AND_OPEN_ID) {
            copy();
            super.buttonPressed(buttonId);
            openIssues.run();
            return;
        }
        super.buttonPressed(buttonId);
    }

    /** Puts the report on the system clipboard as text. */
    private void copy() {
        Clipboard clipboard = new Clipboard(getShell().getDisplay());
        try {
            clipboard.setContents(new Object[] {report}, new Transfer[] {TextTransfer.getInstance()});
        } finally {
            clipboard.dispose();
        }
    }

    /** The report's text field (tests). */
    Text reportText() {
        return text;
    }
}
