// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.eclipse.jface.preference.PreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;

/**
 * What the page tests share: a preference store of their own, and finding, pressing and waiting
 * for widgets. The pages' bodies run here without a workbench (prefs/package-info, "Two classes per
 * page"), so nothing a test changes reaches the plug-in's real store.
 */
final class PageTesting {

    private PageTesting() {
    }

    /**
     * An in-memory store with the plug-in's defaults ({@link PreferenceDefaults}), as Eclipse's store
     * has them: a page's Restore Defaults then shows the plug-in's values. Never saved.
     */
    static PreferenceStore store() {
        PreferenceStore store = new PreferenceStore();
        PreferenceDefaults.fill(new PreferenceDefaults.Sink() {
            @Override
            public void put(String key, boolean value) {
                store.setDefault(key, value);
            }

            @Override
            public void put(String key, int value) {
                store.setDefault(key, value);
            }
        });
        return store;
    }

    /** Every control of {@code type} under {@code root} that passes {@code test}, in layout order. */
    static <T extends Control> List<T> all(Control root, Class<T> type, Predicate<T> test) {
        List<T> found = new ArrayList<>();
        collect(root, type, test, found);
        return found;
    }

    /** The first control of {@code type} under {@code root} that passes {@code test}, or {@code null}. */
    static <T extends Control> T find(Control root, Class<T> type, Predicate<T> test) {
        List<T> found = all(root, type, test);
        return found.isEmpty() ? null : found.get(0);
    }

    private static <T extends Control> void collect(Control root, Class<T> type, Predicate<T> test, List<T> found) {
        if (type.isInstance(root) && test.test(type.cast(root))) {
            found.add(type.cast(root));
        }
        if (root instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                collect(child, type, test, found);
            }
        }
    }

    /** The push button under {@code root} whose label, mnemonic marks ({@code &}) left out, is {@code label}. */
    static Button pushButton(Control root, String label) {
        Button button = find(root, Button.class,
                b -> (b.getStyle() & SWT.PUSH) != 0 && b.getText().replace("&", "").equals(label));
        if (button == null) {
            throw new AssertionError("no button " + label);
        }
        return button;
    }

    /** Presses {@code button} as a click does: a Selection event naming it. */
    static void press(Button button) {
        Event click = new Event();
        click.widget = button;
        button.notifyListeners(SWT.Selection, click);
    }

    /** Runs the event loop until {@code condition} holds (the section's note arrives through it). */
    static void waitUntil(String what, BooleanSupplier condition) {
        Display display = Display.getDefault();
        long deadline = System.currentTimeMillis() + TestWait.millis();
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out after " + TestWait.millis() + " ms waiting for " + what);
            }
            if (!display.readAndDispatch()) {
                display.timerExec(20, () -> { }); // wake up again soon
                display.sleep();
            }
        }
    }
}
