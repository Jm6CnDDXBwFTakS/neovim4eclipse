// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link LayoutCheck} finds cut-off, overlapping and outside text, and passes a window that fits. */
class LayoutCheckTest {

    private Shell shell;

    @BeforeEach
    void open() {
        shell = new Shell(Display.getDefault());
    }

    @AfterEach
    void close() {
        shell.dispose();
    }

    @Test
    void aPackedWindowFits() {
        shell.setLayout(new GridLayout());
        new Label(shell, SWT.NONE).setText("Download Neovim");
        new Label(shell, SWT.WRAP).setText("A longer note that may wrap onto a second line.");
        shell.pack();
        assertEquals(List.of(), LayoutCheck.problems(shell));
    }

    @Test
    void aLabelNarrowerThanItsTextIsCutOff() {
        Label label = new Label(shell, SWT.NONE);
        label.setText("Get Neovim 0.12.6...");
        label.setBounds(0, 0, 10, 40);
        shell.setSize(200, 100);
        List<String> problems = LayoutCheck.problems(shell);
        assertTrue(problems.size() == 1 && problems.get(0).startsWith("Label 'Get Neovim 0.12.6...' is cut off"),
                problems.toString());
    }

    @Test
    void aWrappingLabelTooLowForItsLinesIsCutOff() {
        Label label = new Label(shell, SWT.WRAP);
        label.setText("one two three four five six seven eight nine ten eleven twelve");
        int line = label.computeSize(SWT.DEFAULT, SWT.DEFAULT).y;
        label.setBounds(0, 0, 60, line); // wraps onto several lines at 60 pixels, but has room for one
        shell.setSize(200, 200);
        assertTrue(LayoutCheck.problems(shell).get(0).contains("is cut off"), LayoutCheck.problems(shell).toString());
    }

    @Test
    void overlappingTextsAndTextOverTheEdgeAreReported() {
        shell.setSize(300, 200);
        int width = shell.getClientArea().width;
        Label first = new Label(shell, SWT.NONE);
        first.setText("first");
        first.setBounds(0, 0, 100, 30);
        Label second = new Label(shell, SWT.NONE);
        second.setText("second");
        second.setBounds(50, 10, 100, 30);
        Label outside = new Label(shell, SWT.NONE);
        outside.setText("outside");
        outside.setBounds(width - 20, 100, 100, 30);
        List<String> problems = LayoutCheck.problems(shell);
        assertTrue(problems.contains("Label 'second' overlaps Label 'first'"), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("Label 'outside' reaches outside its parent")),
                problems.toString());
    }
}
