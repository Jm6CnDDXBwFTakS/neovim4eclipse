// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

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

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
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

    /** An open overlay takes the editor's new colors (a switch to the light theme, say) at its next paint. */
    @Test
    void colorsFollowAChangeOfTheEditorsColors() throws InterruptedException {
        shell.setSize(300, 200);
        host.setBounds(0, 0, 300, 200);
        shell.open();
        OverlayFrame<Text> frame = new OverlayFrame<>(host, parent -> new Text(parent, SWT.SINGLE));
        host.setBackground(new Color(0xFF, 0xFF, 0xFF));
        host.setForeground(new Color(0x10, 0x10, 0x10));

        runUntil("the overlay's light colors",
                () -> rgb(frame.content().getBackground()) == OverlayColors.background(0xFFFFFF));
        assertEquals(OverlayColors.border(0xFFFFFF, 0x101010), rgb(frame.control().getBackground()));
        assertEquals(0x101010, rgb(frame.content().getForeground()), "the editor's text color follows");
    }

    /** A text color the owner set itself (the message line's error color) stays through a change. */
    @Test
    void anOwnersTextColorIsKept() throws InterruptedException {
        shell.setSize(300, 200);
        host.setBounds(0, 0, 300, 200);
        shell.open();
        OverlayFrame<Text> frame = new OverlayFrame<>(host, parent -> new Text(parent, SWT.SINGLE));
        frame.useOwnForeground(new Color(0xFF, 0x40, 0x40));
        host.setBackground(new Color(0xFF, 0xFF, 0xFF));
        host.setForeground(new Color(0x10, 0x10, 0x10));

        runUntil("the overlay's light colors",
                () -> rgb(frame.content().getBackground()) == OverlayColors.background(0xFFFFFF));
        assertEquals(0xFF4040, rgb(frame.content().getForeground()));
    }

    /**
     * An error under which the theme switched, then a plain message, then another switch: the text
     * color is the editor's again and follows the second switch too.
     */
    @Test
    void theEditorsTextColorFollowsAgainAfterAnOwnColor() throws InterruptedException {
        shell.setSize(300, 200);
        host.setBounds(0, 0, 300, 200);
        shell.open();
        OverlayFrame<Text> frame = new OverlayFrame<>(host, parent -> new Text(parent, SWT.SINGLE));
        frame.useOwnForeground(new Color(0xFF, 0x40, 0x40)); // an error
        host.setBackground(new Color(0xFF, 0xFF, 0xFF)); // light theme
        host.setForeground(new Color(0x10, 0x10, 0x10));
        runUntil("the overlay's light colors",
                () -> rgb(frame.content().getBackground()) == OverlayColors.background(0xFFFFFF));

        frame.followForeground(); // a plain message
        assertEquals(0x101010, rgb(frame.content().getForeground()));
        host.setBackground(new Color(0x1E, 0x1E, 0x1E)); // dark again
        host.setForeground(new Color(0xD4, 0xD4, 0xD4));
        host.redraw();
        runUntil("the overlay's dark colors",
                () -> rgb(frame.content().getBackground()) == OverlayColors.background(0x1E1E1E));
        assertEquals(0xD4D4D4, rgb(frame.content().getForeground()), "the editor's text color follows");
    }

    /** During the error flash the background under the tint is the editor's: the overlay takes that. */
    @Test
    void theBackgroundUnderAFlashIsTheOneUsed() {
        host.setData(OverlayFrame.BACKGROUND_UNDER_FLASH, host.getBackground());
        host.setBackground(new Color(0x60, 0x1E, 0x1E)); // the tint
        OverlayFrame<Text> frame = new OverlayFrame<>(host, parent -> new Text(parent, SWT.SINGLE));
        assertEquals(OverlayColors.background(0x1E1E1E), rgb(frame.content().getBackground()));
    }

    /**
     * Runs the event loop (paints, posted work) until {@code condition} holds, waiting for the
     * outcome rather than a fixed time (CONTRIBUTING.md, "Tests on a loaded machine").
     */
    private static void runUntil(String what, BooleanSupplier condition) throws InterruptedException {
        Display display = Display.getDefault();
        long deadline = System.currentTimeMillis() + TestWait.millis();
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "timed out waiting for " + what);
            if (!display.readAndDispatch()) {
                Thread.sleep(5);
            }
        }
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
