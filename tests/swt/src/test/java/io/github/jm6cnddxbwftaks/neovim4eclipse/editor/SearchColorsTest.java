// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.OverlayColors;

/**
 * The search highlights' colors ({@link SearchColors}) from a color source of the test's own
 * ({@link FakeSource}, where Eclipse has the theme's registry): a change of the color reaches the
 * highlights, with a box toward the text; a dark editor gets the dark amber without Eclipse's dark
 * theme, also when it sets its colors straight on the widget; nothing is handed on after dispose.
 * That plugin.xml declares the color, and that the theme's registry tells of a change, are the UI
 * tests' ({@code ThemeColorSourceTest}).
 */
class SearchColorsTest {

    private static final RGB GREEN = new RGB(170, 230, 200);

    private Shell shell;
    private StyledText widget;
    private FakeSource source;
    private SearchColors searchColors;

    @BeforeEach
    void open() {
        shell = new Shell(Display.getDefault());
        shell.setSize(300, 200);
        widget = new StyledText(shell, SWT.NONE);
        widget.setBounds(0, 0, 300, 200);
        widget.setText("fox");
        source = new FakeSource();
    }

    @AfterEach
    void close() {
        if (searchColors != null) {
            searchColors.dispose();
        }
        shell.dispose();
    }

    /** With no color answering (no workbench), a light editor gets plugin.xml's light amber. */
    @Test
    void withoutAChosenColorALightEditorGetsTheLightAmber() {
        AtomicReference<RGB> match = new AtomicReference<>();
        searchColors = new SearchColors(widget, source, (m, b) -> match.set(m.getRGB()));
        assertEquals(SearchColors.DEFAULT, match.get());
    }

    /**
     * An editor that sets its colors on the widget itself, without a preference (a TextMate theme
     * in the Generic Editor), gets the amber for them at the next paint.
     */
    @Test
    void colorsSetStraightOnTheWidgetAreSeenAtTheNextPaint() {
        shell.open();
        AtomicReference<RGB> match = new AtomicReference<>();
        searchColors = new SearchColors(widget, source, (m, b) -> match.set(m.getRGB()));
        assertEquals(SearchColors.DEFAULT, match.get(), "a light widget: the light amber");

        widget.setBackground(new Color(30, 30, 30));
        // Paint now rather than wait for the system to: a hidden or locked screen may never paint.
        widget.redraw();
        widget.update();
        int dark = OverlayColors.SEARCH_MATCH_DARK;
        RGB darkAmber = new RGB((dark >> 16) & 0xFF, (dark >> 8) & 0xFF, dark & 0xFF);
        waitUntil("the dark amber after the widget turned dark", () -> darkAmber.equals(match.get()));
    }

    /** A change in Colors and Fonts (the source's new value) is handed on, with a box toward the text. */
    @Test
    void aChangeOfTheColorReachesTheHighlights() {
        AtomicReference<RGB> match = new AtomicReference<>();
        AtomicReference<RGB> box = new AtomicReference<>();
        source.color = new RGB(200, 160, 240); // not the default, so the source is what answers
        searchColors = new SearchColors(widget, source, (m, b) -> {
            match.set(m.getRGB());
            box.set(b.getRGB());
        });
        assertEquals(source.color, match.get(), "the color now, at once");

        source.change(GREEN);
        waitUntil("the new color handed on", () -> GREEN.equals(match.get()));
        assertEquals(searchColors.box(searchColors.match()).getRGB(), box.get(), "the box follows the new color");
    }

    /** A change heard on another thread (a preference import, a Job) is handed on on the UI thread. */
    @Test
    void aChangeHeardOnAnotherThreadIsHandedOnOnTheUiThread() throws InterruptedException {
        List<Thread> threads = new ArrayList<>();
        searchColors = new SearchColors(widget, source, (m, b) -> threads.add(Thread.currentThread()));
        Thread other = new Thread(() -> source.change(GREEN));
        other.start();
        other.join(TestWait.millis());
        waitUntil("the change handed on", () -> threads.size() == 2);
        assertEquals(widget.getDisplay().getThread(), threads.get(1));
    }

    /** After dispose, an update already queued hands on nothing, and the source is no longer heard. */
    @Test
    void anUpdateQueuedBeforeDisposeDoesNothing() {
        AtomicInteger calls = new AtomicInteger();
        searchColors = new SearchColors(widget, source, (m, b) -> calls.incrementAndGet());
        source.change(GREEN); // queues an update (asyncExec)
        searchColors.dispose();
        searchColors = null;
        while (widget.getDisplay().readAndDispatch()) {
            // run the queued update, which must do nothing
        }
        assertEquals(1, calls.get(), "only the constructor's colors");
        assertEquals(0, source.listeners.size(), "no listener left behind");
    }

    /** Runs the event loop until {@code condition} holds (updates are queued to it). */
    private static void waitUntil(String what, BooleanSupplier condition) {
        Display display = Display.getDefault();
        long deadline = System.currentTimeMillis() + TestWait.millis();
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out after " + TestWait.millis() + " ms waiting for: " + what);
            }
            if (!display.readAndDispatch()) {
                display.timerExec(10, () -> { }); // wake up again soon
                display.sleep();
            }
        }
    }

    /** A color source the test sets: the chosen color, and its listeners, told of each change. */
    private static final class FakeSource implements SearchColors.Source {

        volatile RGB color;
        final List<Runnable> listeners = new CopyOnWriteArrayList<>();

        @Override
        public RGB chosen() {
            return color;
        }

        @Override
        public Runnable listen(Runnable changed) {
            listeners.add(changed);
            return () -> listeners.remove(changed);
        }

        /** As Colors and Fonts does: a new value, then the listeners, on the calling thread. */
        void change(RGB to) {
            color = to;
            listeners.forEach(Runnable::run);
        }
    }
}
