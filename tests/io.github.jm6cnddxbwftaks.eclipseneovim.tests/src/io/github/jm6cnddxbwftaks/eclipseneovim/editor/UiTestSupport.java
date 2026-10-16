// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.function.BooleanSupplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;

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
