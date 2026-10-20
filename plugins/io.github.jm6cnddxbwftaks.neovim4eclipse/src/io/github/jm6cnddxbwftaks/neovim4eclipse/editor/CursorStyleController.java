// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Caret;
import org.eclipse.swt.widgets.Display;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimModes;

/**
 * Switches between a block caret (normal/visual/operator) and a thin caret (insert/replace).
 *
 * <p>All SWT access is confined to the UI thread. Callers may invoke
 * {@link #updateForMode(String)} from the Neovim input thread; disposed widgets
 * are ignored safely.
 */
public final class CursorStyleController {

    private final StyledText styledText;
    private volatile int charWidth = 8;
    private volatile boolean block = true;
    private volatile boolean disposed;

    public CursorStyleController(StyledText styledText) {
        this.styledText = styledText;
        runOnUi(() -> {
            measureCharWidth();
            apply(true);
        });
    }

    /** Stop accepting updates (editor detached or widget gone). */
    public void dispose() {
        disposed = true;
    }

    /**
     * @param nvimMode raw mode from Neovim ({@code n}, {@code i}, {@code v}, …)
     */
    public void updateForMode(String nvimMode) {
        if (disposed) {
            return;
        }
        block = !VimModes.usesThinCaret(nvimMode);
        runOnUi(() -> {
            if (disposed || isWidgetDisposed()) {
                return;
            }
            apply(block);
        });
    }

    private void apply(boolean blockCaret) {
        if (isWidgetDisposed()) {
            return;
        }
        measureCharWidth();
        Caret caret = styledText.getCaret();
        if (caret == null || caret.isDisposed()) {
            caret = new Caret(styledText, SWT.NONE);
            styledText.setCaret(caret);
        }
        int height = styledText.getLineHeight();
        int width = blockCaret ? charWidth : Math.max(2, charWidth / 6);
        if (!caret.isDisposed()) {
            caret.setSize(width, height);
        }
    }

    private void measureCharWidth() {
        if (isWidgetDisposed()) {
            return;
        }
        GC gc = new GC(styledText);
        try {
            Point p = gc.stringExtent("M");
            charWidth = Math.max(4, p.x);
        } finally {
            gc.dispose();
        }
    }

    private boolean isWidgetDisposed() {
        try {
            return styledText == null || styledText.isDisposed();
        } catch (Exception e) {
            return true;
        }
    }

    private void runOnUi(Runnable r) {
        if (disposed || isWidgetDisposed()) {
            return;
        }
        Display display;
        try {
            display = styledText.getDisplay();
        } catch (Exception e) {
            return;
        }
        if (display == null || display.isDisposed()) {
            display = Display.getDefault();
        }
        if (display == null || display.isDisposed()) {
            return;
        }
        if (display.getThread() == Thread.currentThread()) {
            if (!disposed && !isWidgetDisposed()) {
                r.run();
            }
        } else {
            display.asyncExec(() -> {
                if (!disposed && !isWidgetDisposed()) {
                    r.run();
                }
            });
        }
    }
}
