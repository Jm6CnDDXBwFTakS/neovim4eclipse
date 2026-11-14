// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
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

    /**
     * A client area that does not start at 0, as a Windows Group's (below its title): a child is
     * inside when it lies within the area from the area's own origin. The numbers are a Windows
     * group's, where the last row once read as 10 px outside.
     */
    @Test
    void aClientAreaIsTakenFromItsOwnOrigin() {
        Rectangle group = new Rectangle(3, 15, 608, 217); // y runs from 15 to 232
        assertTrue(LayoutCheck.inside(new Rectangle(8, 182, 598, 45), group), "bottom 227, inside 232");
        assertFalse(LayoutCheck.inside(new Rectangle(8, 190, 598, 45), group), "bottom 235, past 232");
        assertFalse(LayoutCheck.inside(new Rectangle(8, 10, 598, 20), group), "top 10, above the area's 15");
        assertTrue(LayoutCheck.inside(new Rectangle(0, 0, 100, 20), new Rectangle(0, 0, 100, 20)), "an area at 0");
    }

    @Test
    void aPackedWindowFits() {
        shell.setLayout(new GridLayout());
        new Label(shell, SWT.NONE).setText("Download Neovim");
        new Label(shell, SWT.WRAP).setText("A longer note that may wrap onto a second line.");
        shell.pack();
        assertEquals(List.of(), LayoutCheck.problems(shell));
    }

    /**
     * A parent of a known size for a check: a Composite, not the shell. On GTK a shell's new size
     * reaches its client area only once the window system confirms it, and on a CI runner that can
     * come after the check: the shell then still reads 94x1, and every child "reaches outside" it.
     * A Composite's setBounds takes effect at once.
     */
    private Composite area(int width, int height) {
        Composite area = new Composite(shell, SWT.NONE);
        area.setBounds(0, 0, width, height);
        return area;
    }

    @Test
    void aLabelNarrowerThanItsTextIsCutOff() {
        Composite area = area(200, 100);
        Label label = new Label(area, SWT.NONE);
        label.setText("Get Neovim 0.12.6...");
        label.setBounds(0, 0, 10, 40);
        List<String> problems = LayoutCheck.problems(area);
        assertTrue(problems.size() == 1 && problems.get(0).startsWith("Label 'Get Neovim 0.12.6...' is cut off"),
                problems.toString());
    }

    @Test
    void aWrappingLabelTooLowForItsLinesIsCutOff() {
        Composite area = area(200, 200);
        Label label = new Label(area, SWT.WRAP);
        label.setText("one two three four five six seven eight nine ten eleven twelve");
        int line = label.computeSize(SWT.DEFAULT, SWT.DEFAULT).y;
        label.setBounds(0, 0, 60, line); // wraps onto several lines at 60 pixels, but has room for one
        List<String> problems = LayoutCheck.problems(area);
        assertTrue(problems.size() == 1 && problems.get(0).contains("is cut off"), problems.toString());
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
