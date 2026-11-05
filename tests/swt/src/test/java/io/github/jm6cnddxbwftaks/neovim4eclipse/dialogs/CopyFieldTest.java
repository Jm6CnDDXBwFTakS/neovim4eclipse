// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link CopyField}: a read-only field and a Copy button, and no selection when the field gains focus. */
class CopyFieldTest {

    private Shell shell;

    @BeforeEach
    void open() {
        shell = new Shell(Display.getDefault());
        shell.setLayout(new FillLayout());
    }

    @AfterEach
    void close() {
        shell.dispose();
    }

    @Test
    void theFieldIsReadOnlyAndTheButtonSaysCopy() {
        Composite row = CopyField.create(shell, "-vmargs -Dneovim4eclipse.nvim=/path/to/nvim");
        Text field = (Text) row.getChildren()[0];
        Button copy = (Button) row.getChildren()[1];
        assertEquals("-vmargs -Dneovim4eclipse.nvim=/path/to/nvim", field.getText());
        assertTrue((field.getStyle() & SWT.READ_ONLY) != 0);
        assertEquals(CopyField.COPY, copy.getText());
    }

    /**
     * A field that gains focus (the first control of a page that opens) shows no selection, even
     * where the system selects a focused field's text whole (macOS); the caret goes to the start.
     */
    @Test
    void focusLeavesTheTextUnselected() {
        Composite row = CopyField.create(shell, "-vmargs -Dneovim4eclipse.nvim=/path/to/nvim");
        Text field = (Text) row.getChildren()[0];
        shell.open();
        field.selectAll(); // as macOS does on focus
        field.notifyListeners(SWT.FocusIn, null);
        Display display = shell.getDisplay();
        while (display.readAndDispatch()) {
            // run the clearing queued by the focus listener
        }
        assertEquals(0, field.getSelectionCount(), "nothing selected");
        assertEquals(0, field.getCaretPosition(), "the caret at the start");
    }
}
