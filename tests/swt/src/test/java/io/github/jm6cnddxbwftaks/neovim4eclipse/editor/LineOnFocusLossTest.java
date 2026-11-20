// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;

/**
 * {@link LineOnFocusLoss} on real widgets: the keyboard going from the editor's widget to another
 * control of Eclipse abandons an open line, once; with no line open, or the focus back at once,
 * nothing is abandoned. Another application taking the keyboard, which keeps the line, needs a
 * window manager and a person: the {@code ime-keys} card, step 3.
 */
class LineOnFocusLossTest {

    private static final long TIMEOUT_MS = TestWait.millis();

    private Shell shell;
    private StyledText text;
    private Text other;
    private final AtomicInteger abandoned = new AtomicInteger();
    private boolean lineOpen = true;
    private LineOnFocusLoss watch;

    @BeforeEach
    void open() {
        shell = new Shell(Display.getDefault());
        shell.setLayout(new FillLayout());
        text = new StyledText(shell, SWT.MULTI);
        other = new Text(shell, SWT.SINGLE);
        shell.setSize(300, 200);
        shell.open();
        watch = new LineOnFocusLoss(text, () -> lineOpen, abandoned::incrementAndGet);
        KeyboardFocus.require(text, TIMEOUT_MS);
    }

    @AfterEach
    void close() {
        watch.dispose();
        shell.dispose();
    }

    private static void drain() {
        while (Display.getDefault().readAndDispatch()) {
            // runs the asyncExec the focus change queued
        }
    }

    /** Runs the event loop until {@code abandoned} reaches {@code count}, or the test's wait ran out. */
    private void awaitAbandoned(int count) {
        long end = System.currentTimeMillis() + TIMEOUT_MS;
        while (abandoned.get() < count && System.currentTimeMillis() < end) {
            if (!Display.getDefault().readAndDispatch()) {
                Display.getDefault().sleep();
            }
        }
    }

    @Test
    void theKeyboardGoingToAnotherControlAbandonsTheOpenLine() {
        KeyboardFocus.require(other, TIMEOUT_MS);
        // The FocusOut as the widget gets it when the keyboard goes: sent here as well, since on
        // xvfb (no window manager) the first focus change of a new shell may come without one.
        text.notifyListeners(SWT.FocusOut, new org.eclipse.swt.widgets.Event());
        awaitAbandoned(1);
        drain();
        assertEquals(true, abandoned.get() >= 1, "abandoned");
    }

    @Test
    void withNoLineOpenNothingIsAbandoned() {
        lineOpen = false;
        KeyboardFocus.require(other, TIMEOUT_MS);
        drain();
        assertEquals(0, abandoned.get());
    }

    /** The focus back before the check runs (a transient change): the line stays. */
    @Test
    void theFocusBackAtOnceKeepsTheLine() {
        other.setFocus();
        text.setFocus(); // before the queued check
        drain();
        assertEquals(0, abandoned.get());
    }

    /** Another of Eclipse's windows took the keyboard (a detached view, a dialog): the line is abandoned too. */
    @Test
    void anotherEclipseWindowTakingTheKeyboardAbandonsTheLine() {
        Shell another = new Shell(Display.getDefault());
        another.setLayout(new FillLayout());
        Text field = new Text(another, SWT.SINGLE);
        another.setSize(200, 100);
        another.open();
        try {
            KeyboardFocus.require(field, TIMEOUT_MS);
            text.notifyListeners(SWT.FocusOut, new org.eclipse.swt.widgets.Event()); // as in the test above
            awaitAbandoned(1);
            assertEquals(true, abandoned.get() >= 1, "abandoned");
        } finally {
            another.dispose();
        }
    }

    @Test
    void afterDisposeNothingIsWatched() {
        watch.dispose();
        KeyboardFocus.require(other, TIMEOUT_MS);
        drain();
        assertEquals(0, abandoned.get());
    }
}
