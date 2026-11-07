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
 *
 * <p><b>Putting Eclipse's caret back.</b> The caret's width before the first change is remembered,
 * and {@link #dispose()} gives it back. Without that, an editor left by Neovim (Detach Neovim, a
 * restart of Neovim, a multi-page editor switching to another page) kept the block caret of
 * normal mode although its keys were Eclipse's again. Example: Eclipse's caret is 1 pixel wide;
 * Neovim's normal mode makes it one {@code M} wide; after Detach Neovim it is 1 pixel again.
 *
 * <p>SWT only, no workbench: {@code CursorStyleControllerTest} (tests/swt) runs it on a real
 * {@link StyledText}.
 */
public final class CursorStyleController {

    private final StyledText styledText;
    private volatile int charWidth = 8;
    private volatile boolean block = true;
    private volatile boolean disposed;
    /**
     * Eclipse's own caret, as it was before the first change: the caret and its width, for
     * {@link #dispose()} to give back. {@code null} until then (the first change runs on the UI
     * thread, later than the constructor when that runs on another). UI thread.
     */
    private Caret originalCaret;
    private int originalWidth = -1;

    public CursorStyleController(StyledText styledText) {
        this.styledText = styledText;
        runOnUi(() -> {
            measureCharWidth();
            apply(true);
        });
    }

    /**
     * Stops accepting updates (editor detached or widget gone) and gives the editor its own caret
     * back, the width it had before Neovim (see the class comment). Any thread: the caret is put back
     * on the UI thread, after the updates queued before, which then do nothing.
     */
    public void dispose() {
        disposed = true;
        Display display = displayOf();
        if (display == null) {
            return;
        }
        if (display.getThread() == Thread.currentThread()) {
            restoreOriginalCaret();
        } else {
            display.asyncExec(this::restoreOriginalCaret);
        }
    }

    /** Puts {@link #originalCaret} back at its width, if the widget and caret are still there. UI thread. */
    private void restoreOriginalCaret() {
        if (originalWidth < 0 || isWidgetDisposed()) {
            return;
        }
        Caret caret = styledText.getCaret();
        if (originalCaret != null && !originalCaret.isDisposed() && caret != originalCaret) {
            styledText.setCaret(originalCaret); // apply() made a caret of its own: drop it
            if (caret != null && !caret.isDisposed()) {
                caret.dispose();
            }
            caret = originalCaret;
        }
        if (caret != null && !caret.isDisposed()) {
            caret.setSize(originalWidth, styledText.getLineHeight());
        }
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
        if (originalWidth < 0 && caret != null && !caret.isDisposed()) {
            originalCaret = caret;
            originalWidth = caret.getSize().x;
        }
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

    /** The widget's display, or {@code null} when the widget or every display is gone. */
    private Display displayOf() {
        if (isWidgetDisposed()) {
            return null;
        }
        Display display;
        try {
            display = styledText.getDisplay();
        } catch (Exception e) {
            return null;
        }
        if (display == null || display.isDisposed()) {
            display = Display.getDefault();
        }
        return display == null || display.isDisposed() ? null : display;
    }

    private void runOnUi(Runnable r) {
        if (disposed) {
            return;
        }
        Display display = displayOf();
        if (display == null) {
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
