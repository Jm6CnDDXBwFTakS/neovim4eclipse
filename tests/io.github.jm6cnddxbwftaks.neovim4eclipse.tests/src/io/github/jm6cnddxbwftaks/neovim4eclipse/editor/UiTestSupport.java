// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;

/**
 * Helpers for tests that run on the SWT UI thread inside a workbench.
 *
 * <p>A UI test cannot simply sleep: the events it is waiting for (Neovim's answers, applied with
 * {@code Display.asyncExec}) are only processed while the UI thread runs the event loop. So waiting
 * means spinning {@link Display#readAndDispatch()} until a condition holds.
 */
final class UiTestSupport {

    private UiTestSupport() {
    }

    /** Runs the event loop until {@code condition} holds; fails after {@code timeoutMs}. */
    static void waitUntil(String what, long timeoutMs, BooleanSupplier condition) {
        waitUntil(what, timeoutMs, condition, () -> "");
    }

    /** As {@link #waitUntil(String, long, BooleanSupplier)}, adding {@code actual} to the failure message. */
    static void waitUntil(String what, long timeoutMs, BooleanSupplier condition,
            java.util.function.Supplier<String> actual) {
        Display display = Display.getCurrent();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out after " + timeoutMs + " ms waiting for: " + what + actual.get());
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

    /** How many empty event-loop turns in a row count as "the UI thread has nothing left to do". */
    private static final int QUIET_TURNS = 3;

    /**
     * Waits until everything queued on the session before this call is done and the UI thread has
     * applied what it produced. Use it instead of a fixed {@link #spin} after keys or a document push.
     *
     * <p>How it knows, in two steps:
     * <ol>
     *   <li>A no-op is queued on the session's input thread. That thread runs one task at a time in
     *       order, and a key's task returns only after Neovim answered the requests that follow the
     *       key ({@code nvim_get_mode}, and the state read when Neovim is not waiting for more). So
     *       when the no-op runs, Neovim has handled every earlier key, and the notifications it sent
     *       before those answers (buffer lines, mode) have arrived.</li>
     *   <li>Those notifications reach the editor with {@code Display.asyncExec}. The event loop is run
     *       until it has been empty for {@value #QUIET_TURNS} turns in a row, so the posted work has
     *       been applied to the widget and the document.</li>
     * </ol>
     *
     * <p>Example: {@code type(widget, "ggVj"); settle(session);} then the selection is on the widget.
     * Safe while Neovim waits for more keys (after {@code g}): the no-op sends nothing to Neovim.
     */
    static void settle(NeovimSession session, long timeoutMs) {
        AtomicBoolean reached = new AtomicBoolean();
        session.runAsync("test: settle", () -> reached.set(true));
        waitUntil("the session to finish the work queued before", timeoutMs, reached::get);
        Display display = Display.getCurrent();
        long deadline = System.currentTimeMillis() + timeoutMs;
        int quiet = 0;
        while (quiet < QUIET_TURNS) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out after " + timeoutMs + " ms waiting for the UI thread to become idle");
            }
            if (display.readAndDispatch()) {
                quiet = 0;
            } else {
                quiet++;
                try {
                    Thread.sleep(5); // let an RPC thread post work it has just received
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("interrupted");
                }
            }
        }
    }

    /** How often {@link #requireKeyboardFocus} asks again while the focus has not arrived. */
    private static final long FOCUS_RETRY_MS = 250;

    /**
     * Makes {@code control}'s window the active one, gives {@code control} the keyboard focus, and
     * waits until SWT reports that it has it. Fails with "lost keyboard focus" and who has the focus
     * instead (see {@link #focusReport}) if it never arrives.
     *
     * <p>Why a test needs this: behavior that depends on focus (the Vim key context is active only
     * while the editor's text widget has focus; the {@code :} box closes when it loses focus) breaks
     * when the test window is not the active window. That happens on a real desktop while the
     * developer works in another window, and on {@code xvfb-run}, which has no window manager: when
     * a shell that had the X input focus closes (a dialog, a popup), nobody hands the focus back to
     * the workbench window. Then {@code Control.setFocus()} only remembers the control for when its
     * shell is activated again, and {@code isFocusControl()} stays {@code false}, because SWT counts
     * the focus only inside the active shell.
     *
     * <p>{@code Shell.forceActive()} asks for the activation even against the window manager's
     * focus-stealing rules (on X11 without a window manager it sets the input focus directly). The
     * request is asynchronous, so it is repeated every {@value #FOCUS_RETRY_MS} ms until it arrives.
     *
     * <p>Example: {@code requireKeyboardFocus(widget, TIMEOUT_MS);} before a step whose result
     * depends on the editor having focus.
     */
    static void requireKeyboardFocus(Control control, long timeoutMs) {
        Display display = control.getDisplay();
        Shell shell = control.getShell();
        long deadline = System.currentTimeMillis() + timeoutMs;
        long nextRequest = 0;
        while (!control.isFocusControl()) {
            long now = System.currentTimeMillis();
            if (now > deadline) {
                fail("Timed out after " + timeoutMs + " ms: " + describe(control) + " lost keyboard focus and did not get it back; "
                        + focusReport(control));
            }
            if (now >= nextRequest) {
                if (display.getActiveShell() != shell) {
                    shell.forceActive();
                }
                control.forceFocus();
                nextRequest = now + FOCUS_RETRY_MS;
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
     * Where the keyboard focus is, for a failure message. Example: {@code "active shell: none (the
     * test's window is 'Eclipse'); focus control: none"}, which means the test's window is not the
     * active window.
     */
    static String focusReport(Control expected) {
        Display display = Display.getCurrent(); // not expected.getDisplay(): it throws once expected is disposed
        Shell active = display.getActiveShell();
        Shell own = expected.isDisposed() ? null : expected.getShell();
        String shell = active == null ? "none" : active == own ? "the test's window" : "'" + active.getText() + "'";
        String ownTitle = own == null ? "disposed" : "'" + own.getText() + "'";
        return "active shell: " + shell + " (the test's window is " + ownTitle + "); focus control: "
                + describe(display.getFocusControl());
    }

    private static String describe(Control control) {
        return control == null ? "none" : control.isDisposed() ? "a disposed control" : control.getClass().getSimpleName();
    }

    /** Runs the event loop for a while (lets queued work finish). */
    static void spin(long millis) {
        long end = System.currentTimeMillis() + millis;
        waitUntil("time to pass", millis + 5000, () -> System.currentTimeMillis() >= end);
    }

    /**
     * Sends one key press to the widget as if typed. {@code notifyListeners(SWT.KeyDown)} goes through
     * the widget's own key handling, verify-key listeners (the plug-in's KeyInterceptor) included;
     * it bypasses the workbench's key-binding dispatcher, so it is not for Ctrl keys.
     */
    static void type(StyledText widget, char character) {
        Event event = new Event();
        event.character = character;
        event.keyCode = Character.isLetter(character) ? Character.toLowerCase(character) : character;
        event.stateMask = Character.isUpperCase(character) ? SWT.SHIFT : 0;
        event.doit = true; // as for a real key press; a new Event defaults to false (key ignored)
        widget.notifyListeners(SWT.KeyDown, event);
    }

    /** Types each character of {@code keys}. */
    static void type(StyledText widget, String keys) {
        for (char c : keys.toCharArray()) {
            type(widget, c);
        }
    }

    /** Sends Escape. */
    static void escape(StyledText widget) {
        Event event = new Event();
        event.character = SWT.ESC;
        event.keyCode = SWT.ESC;
        event.doit = true;
        widget.notifyListeners(SWT.KeyDown, event);
    }
}
