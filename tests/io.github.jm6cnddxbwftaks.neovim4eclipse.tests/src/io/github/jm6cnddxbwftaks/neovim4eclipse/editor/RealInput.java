// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.AffineTransform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;

/**
 * Keys and clicks sent through the operating system, not straight to a widget as
 * {@code notifyListeners} does: they come back the way a keyboard's and a mouse's do, through
 * the system, Eclipse's key bindings and SWT's focus and activation handling. A synthetic event
 * skips all of that, so a test of what a real click or key does (the editor keeping the focus,
 * Eclipse's Close not taking Ctrl+W) needs these.
 *
 * <p>Two ways in, one per kind:
 * <ul>
 * <li><b>Keys:</b> SWT's {@link Display#post}. On Windows {@code SendInput}; on Linux (GTK 3)
 *     SWT puts the key event into GTK's queue for the window with the keyboard, through GTK's
 *     handling and Eclipse's key bindings.</li>
 * <li><b>Clicks:</b> the JDK's {@link Robot}, since SWT's {@code post} delivers no click on GTK
 *     (it warps the pointer, but its button event never reaches the widget, and it carries no
 *     modifier). Robot moves and presses through the system itself: the XTEST extension on X11
 *     (also under {@code xvfb-run}), {@code SendInput} on Windows. A key held with it (Ctrl for a
 *     Ctrl+click) is a real key press too. No AWT window opens; Robot only sends input.</li>
 * </ul>
 * A Wayland session takes neither (no pointer a client may move), and macOS needs the
 * Accessibility permission, so the tests that use this are not run on macOS and skip where the
 * pointer does not move.
 *
 * <p>An event sent this way goes to whatever window has the keyboard or lies under the pointer, as
 * a real one would: on a developer's desktop, a key sent while a browser has the focus would act
 * on the browser. So every send checks, right before it, that the test Eclipse's window is the
 * active one and, for a click, that the pointer is over that window ({@link #requireFront};
 * no check can close the moment between it and the system taking the event). On Windows and a
 * real X11 desktop the real pointer moves during these tests.
 */
final class RealInput {

    private RealInput() {
    }

    /**
     * Gives {@code control} the keyboard in the active window ({@link UiTestSupport#requireKeyboardFocus})
     * and fails unless the test Eclipse's window is the active one: a test calls this once before
     * its first gesture, and each send checks the window again ({@link #requireActive}).
     */
    static void requireFront(Control control, long timeoutMs) {
        UiTestSupport.requireKeyboardFocus(control, timeoutMs);
        requireActive(control);
        assertTrue(control.isFocusControl(), "the control has the keyboard");
    }

    /** Fails unless {@code control}'s window is the active one: what a send checks right before it. */
    private static void requireActive(Control control) {
        assertSame(control.getShell(), control.getDisplay().getActiveShell(),
                "the test Eclipse's window is the active one (else the input would reach another program)");
    }

    /**
     * One key event through the system, for {@code target}; {@code keyCode} an SWT key code such as
     * {@code SWT.CTRL}. A press needs {@code target}'s window to be the active one; a release is
     * always sent, since letting a key go does nothing in another program, and a test that presses a
     * modifier lets it go in a {@code finally}, so that a failure in between does not leave it held.
     */
    static void key(Control target, int type, int keyCode) {
        if (type == SWT.KeyDown) {
            requireActive(target);
        }
        Event event = new Event();
        event.type = type;
        event.keyCode = keyCode;
        assertTrue(target.getDisplay().post(event), "the system took the key event");
    }

    /**
     * A letter key pressed and let go through the system for {@code target} (posted by its
     * character): the press after the check {@link #key} makes, the release always.
     */
    static void letter(Control target, char letter) {
        requireActive(target);
        for (int type : new int[] { SWT.KeyDown, SWT.KeyUp }) {
            Event event = new Event();
            event.type = type;
            event.character = letter;
            assertTrue(target.getDisplay().post(event), "the system took the key event");
        }
    }

    /**
     * Moves the pointer to ({@code x}, {@code y}) in {@code control}'s own coordinates and clicks
     * the left button there (press and release) through the system, with Ctrl held when
     * {@code ctrl}. Answers {@code false}, sending nothing more, when the system did not move the
     * pointer at all (a Wayland session, a headless Java), on which a test skips; fails, naming
     * both points, when it moved it somewhere else. Example:
     * {@code RealInput.leftClick(widget, 10, 5, false)} clicks near the text's top-left corner.
     */
    static boolean leftClick(Control control, int x, int y, boolean ctrl) {
        Display display = control.getDisplay();
        Point at = control.toDisplay(x, y);
        Robot robot = robot();
        if (robot == null) {
            return false;
        }
        requireActive(control);
        Point before = display.getCursorLocation();
        java.awt.Point awt = awtPoint(control, at);
        robot.mouseMove(awt.x, awt.y);
        // The press waits until SWT sees the pointer there: pressed before, on a busy system, it
        // could land where the pointer was. A point off is still there: under a fractional scale
        // (150%) the two conversions each round, and a click a point beside the aim lands in the
        // same cell.
        if (!UiTestSupport.waitUntilOrGiveUp(TestWait.millis(), () -> near(display.getCursorLocation(), at))) {
            Point now = display.getCursorLocation();
            if (now.equals(before) && !near(before, at)) {
                return false; // the system did not take the move
            }
            fail("the pointer went to " + now + ", not " + at + " (sent to AWT " + awt + "): the two coordinate systems disagree");
        }
        requireActive(control);
        // Under the pointer: a control of the test Eclipse's window, not another program's window
        // on top. Not necessarily the control aimed at: a float the editor draws over it (a
        // picker's border window) takes the click as a real one would.
        Control under = display.getCursorControl();
        assertTrue(under != null && under.getShell() == control.getShell(),
                "the pointer is over the test Eclipse's window, nothing of another program on top: " + under);
        if (ctrl) {
            robot.keyPress(KeyEvent.VK_CONTROL);
        }
        try {
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        } finally {
            if (ctrl) {
                robot.keyRelease(KeyEvent.VK_CONTROL);
            }
        }
        return true;
    }

    /**
     * {@code at}, an SWT point on the screen, in the units Robot takes. SWT counts in points,
     * device pixels divided by the monitor's zoom as SWT applies it ({@code Monitor.getZoom}:
     * 100 at 100%; on Windows at 150%, 150, or 100 where SWT rounds scaling away); AWT counts in
     * device pixels divided by its own scale (its screen's default transform). Both are 1 under
     * {@code xvfb-run}. Example: SWT at zoom 100 on a 150% screen whose AWT scale is 1.5: SWT's
     * (300, 150) is device (300, 150), which Robot takes as (200, 100).
     */
    private static java.awt.Point awtPoint(Control control, Point at) {
        double swt = control.getMonitor().getZoom() / 100.0;
        AffineTransform awt = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getDefaultTransform();
        return new java.awt.Point((int) Math.round(at.x * swt / awt.getScaleX()), (int) Math.round(at.y * swt / awt.getScaleY()));
    }

    /** Whether {@code a} is {@code b} or a point beside it, in either direction. */
    private static boolean near(Point a, Point b) {
        return Math.abs(a.x - b.x) <= 1 && Math.abs(a.y - b.y) <= 1;
    }

    /** The JDK's Robot, or {@code null} where Java runs headless or the system refuses one. */
    private static Robot robot() {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        try {
            return new Robot();
        } catch (AWTException | SecurityException e) {
            return null;
        }
    }
}
