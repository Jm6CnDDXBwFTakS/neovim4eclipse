// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.OverlayColors;

/** The border and tint of the command line and output popup, on real SWT widgets. */
class OverlayFrameTest {

    private Shell shell;
    private StyledText host;

    @BeforeEach
    void createHost() {
        shell = new Shell(Display.getDefault());
        host = new StyledText(shell, SWT.MULTI);
        host.setBackground(new Color(0x1E, 0x1E, 0x1E));
        host.setForeground(new Color(0xD4, 0xD4, 0xD4));
    }

    @AfterEach
    void dispose() {
        shell.dispose();
    }

    private static int rgb(Color c) {
        return (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
    }

    @Test
    void colorsDeriveFromTheEditor() {
        OverlayFrame<Text> frame = new OverlayFrame<>(host, parent -> new Text(parent, SWT.SINGLE));
        assertEquals(OverlayColors.border(0x1E1E1E, 0xD4D4D4), rgb(frame.control().getBackground()));
        assertEquals(OverlayColors.background(0x1E1E1E), rgb(frame.content().getBackground()));
        assertEquals(host.getFont(), frame.content().getFont());
    }

    @Test
    void contentSitsInsideTheBorder() {
        OverlayFrame<Text> frame = new OverlayFrame<>(host, parent -> new Text(parent, SWT.SINGLE));
        frame.control().setBounds(0, 0, 200, 30);
        ((Composite) frame.control()).layout();
        assertEquals(OverlayFrame.BORDER, frame.content().getBounds().x);
        assertEquals(200 - 2 * OverlayFrame.BORDER, frame.content().getBounds().width);
    }

    @Test
    void disposingTheFrameDisposesTheContent() {
        OverlayFrame<Text> frame = new OverlayFrame<>(host, parent -> new Text(parent, SWT.SINGLE));
        Text content = frame.content();
        frame.dispose();
        assertTrue(frame.isDisposed());
        assertTrue(content.isDisposed());
    }

    @Test
    void bottomGapIsAboutHalfACommandLine() {
        int gap = OverlayFrame.bottomGap(host);
        assertTrue(gap >= OverlayFrame.SIDE_PADDING);
        assertTrue(gap <= OverlayFrame.outerHeight(host.getLineHeight()), "gap " + gap);
    }
}
