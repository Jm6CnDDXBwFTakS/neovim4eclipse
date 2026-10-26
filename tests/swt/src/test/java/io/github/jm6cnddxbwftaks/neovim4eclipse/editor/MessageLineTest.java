// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;

/**
 * The line along the bottom of the editor that shows Neovim's latest message or error, on a real
 * {@link StyledText}: what it shows, where it sits, its colors, what hides it, and the opt-in flash.
 * No workbench or Neovim is needed.
 */
class MessageLineTest {

    private static final int HOST_WIDTH = 400;
    private static final int HOST_HEIGHT = 300;

    private Shell shell;
    private StyledText host;
    private boolean flash;
    private MessageLine line;

    @BeforeEach
    void createHost() {
        shell = new Shell(Display.getDefault());
        shell.setSize(HOST_WIDTH, HOST_HEIGHT);
        host = new StyledText(shell, SWT.MULTI);
        host.setBounds(0, 0, HOST_WIDTH, HOST_HEIGHT);
        host.setText("alpha\nbeta\n");
        shell.open();
        line = new MessageLine(host, () -> flash);
    }

    @AfterEach
    void dispose() {
        line.dispose();
        shell.dispose();
    }

    private CLabel label() {
        return line.frame().content();
    }

    private void key(int keyCode, char character) {
        Event event = new Event();
        event.keyCode = keyCode;
        event.character = character;
        event.doit = true;
        host.notifyListeners(SWT.KeyDown, event);
    }

    /**
     * Runs the event loop, so that timers fire, until {@code condition} holds. Waits for the outcome
     * instead of a fixed time: a timer due in 150 ms can fire much later on a loaded machine, so
     * "run the loop for 450 ms, then check" failed there although nothing was wrong.
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
    void aMessageShowsAlongTheBottomOfTheEditor() {
        line.show("3 lines yanked", false);

        assertTrue(line.isShowing());
        assertEquals("3 lines yanked", label().getText());
        Rectangle bounds = line.frame().control().getBounds();
        Rectangle area = host.getClientArea();
        assertEquals(OverlayFrame.SIDE_PADDING, bounds.x);
        assertEquals(area.width - 2 * OverlayFrame.SIDE_PADDING, bounds.width);
        assertEquals(area.height - OverlayFrame.bottomGap(host), bounds.y + bounds.height, "just above the bottom edge");
        assertTrue(bounds.height < 3 * host.getLineHeight(), "one line high: " + bounds.height);
    }

    @Test
    void anErrorIsDrawnInAnotherColorThanAMessage() {
        line.show("3 lines yanked", false);
        Color plain = label().getForeground();
        assertEquals(host.getForeground().getRGB(), plain.getRGB());
        assertFalse(line.isError());

        line.show("E486: Pattern not found: x", true);
        assertTrue(line.isError());
        assertNotEquals(plain.getRGB(), label().getForeground().getRGB());
    }

    @Test
    void aBlankMessageHidesTheLine() {
        line.show("E1: x", true);
        line.show("  ", false);
        assertFalse(line.isShowing());
        assertNull(line.frame());
        assertEquals("", line.text());
    }

    @Test
    void theNextKeyHidesTheLine() {
        line.show("E1: x", true);
        key('n', 'n');
        assertFalse(line.isShowing());
    }

    @Test
    void aModifierOnItsOwnIsNotTheNextKey() {
        line.show("E1: x", true);
        key(SWT.SHIFT, (char) 0);
        key(SWT.CTRL, (char) 0);
        assertTrue(line.isShowing(), "Shift or Ctrl alone must not hide the message");
    }

    @Test
    void aClickInTheEditorHidesTheLine() {
        line.show("E1: x", true);
        host.notifyListeners(SWT.MouseDown, new Event());
        assertFalse(line.isShowing());
    }

    @Test
    void aClickOnTheLineHidesIt() {
        line.show("E1: x", true);
        label().notifyListeners(SWT.MouseDown, new Event());
        assertFalse(line.isShowing());
    }

    @Test
    void severalLinesBecomeOneAndTheTooltipHasItAll() {
        String longText = "E5108: Error executing lua:\n" + "x".repeat(300);
        line.show(longText, true);
        assertFalse(label().getText().contains("\n"), "one line");
        assertEquals("E5108: Error executing lua: " + "x".repeat(300), label().getToolTipText());
    }

    @Test
    void aNewMessageReplacesTheShownOne() {
        line.show("first", false);
        line.show("E2: second", true);
        assertEquals("E2: second", label().getText());
        assertEquals("E2: second", line.text());
    }

    @Test
    void withTheFlashOnAnErrorTintsTheEditorForAMoment() throws InterruptedException {
        flash = true;
        Color before = host.getBackground();
        line.show("E1: x", true);
        assertTrue(line.isFlashing());
        assertNotEquals(before.getRGB(), host.getBackground().getRGB(), "tinted");
        assertEquals(1, line.flashCount());

        runUntil("the flash to end", () -> !line.isFlashing());
        assertEquals(before.getRGB(), host.getBackground().getRGB(), "back to the editor's own background");
    }

    @Test
    void withTheFlashOnAPlainMessageDoesNotFlash() {
        flash = true;
        line.show("3 lines yanked", false);
        assertFalse(line.isFlashing());
    }

    @Test
    void withoutTheFlashAnErrorDoesNotFlash() {
        Color before = host.getBackground();
        line.show("E1: x", true);
        assertFalse(line.isFlashing());
        assertEquals(before.getRGB(), host.getBackground().getRGB());
    }

    @Test
    void disposingEndsAFlashAndHidesTheLine() {
        flash = true;
        Color before = host.getBackground();
        line.show("E1: x", true);
        line.dispose();
        assertFalse(line.isShowing());
        assertEquals(before.getRGB(), host.getBackground().getRGB());
        key('x', 'x'); // no listener left to fail
    }
}
