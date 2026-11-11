// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.fail;

import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * Keyboard focus for SWT widget tests: makes sure a control has it, and says where it went when it
 * is lost.
 *
 * <p>Why tests need this: SWT counts focus only inside the active window. A test's shell is not
 * active when the developer is typing in another window, or on {@code xvfb-run} (no window manager)
 * after another shell that had the focus closed. Then {@code setFocus()} does nothing visible, and
 * code that reacts to focus behaves differently: the {@code :} box ({@link CommandLineBox}) closes
 * as soon as it loses focus, so the test's next call on it fails with an unexplained "Widget is
 * disposed".
 *
 * <p>The UI test fragment has the same helper in {@code UiTestSupport}; the two modules cannot share
 * test code (one is plain JUnit, the other an OSGi fragment), and this one is small.
 */
final class KeyboardFocus {

    /** How often {@link #require} asks again while the focus has not arrived. */
    private static final long RETRY_MS = 250;

    private KeyboardFocus() {
    }

    /**
     * Activates {@code control}'s shell, gives {@code control} the keyboard focus, and runs the event
     * loop until SWT reports it; fails with "lost keyboard focus" and a {@link #report} otherwise.
     * {@code Shell.forceActive()} is asynchronous and may be refused once, so it is asked again every
     * {@value #RETRY_MS} ms.
     *
     * <p>Example: {@code KeyboardFocus.require(box, TestWait.millis());} right after the box opened.
     */
    static void require(Control control, long timeoutMs) {
        Display display = control.getDisplay();
        Shell shell = control.getShell();
        long deadline = System.currentTimeMillis() + timeoutMs;
        long nextRequest = 0;
        while (!control.isFocusControl()) {
            long now = System.currentTimeMillis();
            if (now > deadline) {
                fail("Timed out after " + timeoutMs + " ms: " + describe(control)
                        + " lost keyboard focus and did not get it back; " + report(display, shell));
            }
            if (now >= nextRequest) {
                if (display.getActiveShell() != shell) {
                    shell.forceActive();
                }
                control.forceFocus();
                nextRequest = now + RETRY_MS;
            }
            if (!display.readAndDispatch()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("interrupted");
                }
            }
        }
    }

    /**
     * Activates {@code shell} again if another window took the activation, and runs the event loop
     * until a control in it has the keyboard focus; returns that control. Unlike {@link #require}
     * it gives no control the focus: an activated window hands it back to the control last given
     * it, so the answer is where the code under test left it.
     *
     * <p>Why: on a desktop with a window manager, the test's window can lose the activation while a
     * test runs, with no other window of the test's own active; the focus is then nowhere
     * ({@code getFocusControl()} null), though the window still knows which control had it. Under
     * {@code xvfb-run} (no window manager) it does not happen. Example: after the {@code :} box
     * closed, {@code assertSame(other, KeyboardFocus.afterActivation(shell, TestWait.millis()))}.
     */
    static Control afterActivation(Shell shell, long timeoutMs) {
        Display display = shell.getDisplay();
        long deadline = System.currentTimeMillis() + timeoutMs;
        long nextRequest = 0;
        while (display.getActiveShell() != shell || display.getFocusControl() == null) {
            long now = System.currentTimeMillis();
            if (now > deadline) {
                fail("Timed out after " + timeoutMs + " ms: the test's window did not get the focus back; "
                        + report(display, shell));
            }
            if (now >= nextRequest && display.getActiveShell() != shell) {
                shell.forceActive();
                nextRequest = now + RETRY_MS;
            }
            if (!display.readAndDispatch()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("interrupted");
                }
            }
        }
        return display.getFocusControl();
    }

    /**
     * Where the keyboard focus is, for a failure message. Example: {@code "active shell: none (not
     * the test's shell); focus control: none"}: another window has the focus, or none does.
     */
    static String report(Display display, Shell testShell) {
        Shell active = display.getActiveShell();
        String shell = active == null ? "none (not the test's shell)"
                : active == testShell ? "the test's shell" : "another shell, '" + active.getText() + "'";
        return "active shell: " + shell + "; focus control: " + describe(display.getFocusControl());
    }

    private static String describe(Control control) {
        return control == null ? "none" : control.isDisposed() ? "a disposed control" : control.getClass().getSimpleName();
    }
}
