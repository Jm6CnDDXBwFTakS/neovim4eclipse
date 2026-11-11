// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

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
 * <p>The UI test fragment has {@link #require}'s counterpart in {@code UiTestSupport}; the two
 * modules cannot share test code (one is plain JUnit, the other an OSGi fragment), and this one is
 * small. The other helpers here serve the {@code :} box's tests, which check where its code moves
 * the focus.
 */
final class KeyboardFocus {

    /** How often {@link #require}, {@link #give} and {@link #whileActive} ask again while the focus has not arrived. */
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
        give(control, control::isFocusControl,
                () -> describe(control) + " lost keyboard focus and did not get it back", timeoutMs);
    }

    /**
     * Gives {@code control} the keyboard focus, activating its shell first, again every
     * {@value #RETRY_MS} ms until {@code done} holds; fails with {@code what} and a {@link #report}
     * after {@code timeoutMs}.
     *
     * <p>Why not {@link #require} when code under test moves the focus on at once: require waits to
     * see {@code control} holding the focus, which it may never be seen doing. Waiting for its focus
     * event instead ends at the first one. Giving it again matters on a desktop with a window
     * manager: SWT can report the window active before the activation has arrived, and a focus
     * given then is replaced by the window's own last one when it does arrive, with no focus event.
     * Example: the {@code :} box in Ex mode takes the focus back from the editor on its focus event:
     * {@code KeyboardFocus.give(host, () -> tookIt[0], () -> "the editor's focus event", TestWait.millis())}.
     */
    static void give(Control control, BooleanSupplier done, Supplier<String> what, long timeoutMs) {
        Shell shell = control.getShell();
        loop(shell, done, what, timeoutMs, () -> {
            activate(shell);
            control.forceFocus();
        });
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
        whileActive(shell, () -> display.getActiveShell() == shell && display.getFocusControl() != null,
                () -> "the test's window did not get the focus back", timeoutMs);
        return display.getFocusControl();
    }

    /**
     * Runs the event loop until {@code done} holds, activating {@code shell} again every
     * {@value #RETRY_MS} ms while another window has the activation; fails with {@code what} and a
     * {@link #report} taken at the time out. It gives no control the focus.
     *
     * <p>Why: a focus that code under test gives (an {@code asyncExec} that focuses the box) counts
     * only in the active window, and on a desktop the window manager can take the activation from
     * the test's window mid-test. Example:
     * {@code KeyboardFocus.whileActive(shell, box::isFocusControl, () -> "the box's focus", TestWait.millis())}.
     */
    static void whileActive(Shell shell, BooleanSupplier done, Supplier<String> what, long timeoutMs) {
        loop(shell, done, what, timeoutMs, () -> activate(shell));
    }

    /** {@code Shell.forceActive()} unless {@code shell} is the active one already. */
    private static void activate(Shell shell) {
        if (shell.getDisplay().getActiveShell() != shell) {
            shell.forceActive();
        }
    }

    /**
     * The event loop of {@link #give} and {@link #whileActive}: runs {@code retry} at once and then
     * every {@value #RETRY_MS} ms until {@code done} holds; sleeps 10 ms when no event is waiting.
     */
    private static void loop(Shell shell, BooleanSupplier done, Supplier<String> what, long timeoutMs, Runnable retry) {
        Display display = shell.getDisplay();
        long deadline = System.currentTimeMillis() + timeoutMs;
        long nextRequest = 0;
        while (!done.getAsBoolean()) {
            long now = System.currentTimeMillis();
            if (now > deadline) {
                fail("Timed out after " + timeoutMs + " ms: " + what.get() + "; " + report(display, shell));
            }
            if (now >= nextRequest) {
                retry.run();
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
