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
 * <p><b>Putting Eclipse's caret back.</b> The caret's width before the first change is remembered
 * (again whenever Eclipse makes a new caret), and {@link #dispose()} gives it back, unless Eclipse
 * has set a new caret since the last change, which then already has Eclipse's shape. Without that, an editor left by Neovim (Detach Neovim, a
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
     * Eclipse's own caret, the latest one a change found, and its width then, for
     * {@link #dispose()} to give back. {@code null} until the first change (which runs on the UI
     * thread, later than the constructor when that runs on another). UI thread.
     *
     * <p>Eclipse may replace the caret while Neovim has the editor: {@code AbstractTextEditor}
     * makes a new one when insert and overwrite are toggled (the Insert key), or the caret
     * preferences change. The next change then finds that one, and records it and its width here.
     */
    private Caret eclipseCaret;
    private int eclipseWidth = -1;
    /**
     * The caret this controller made, when the widget had none; {@code null} if it never had to.
     * UI thread.
     */
    private Caret ownCaret;

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

    /**
     * The width of a caret Eclipse never measured for us: 1 pixel, the width of StyledText's own
     * default caret and of {@code AbstractTextEditor}'s insert caret ({@code SINGLE_CARET_WIDTH},
     * without the "wide caret" preference). For the caret this controller made when the widget had
     * none (see {@link #restoreOriginalCaret}).
     */
    static final int ECLIPSE_THIN_WIDTH = 1;

    /**
     * Gives Eclipse's caret back its width, if the widget still shows a caret this controller
     * changed. UI thread. Three cases:
     * <ul>
     *   <li>The widget shows {@link #eclipseCaret}: back to {@link #eclipseWidth}.</li>
     *   <li>It shows {@link #ownCaret}: Eclipse's caret goes back in its place, and this one goes.
     *       When there is none to give back (the widget had no caret, or Eclipse's was disposed),
     *       this one stays, as the editor's caret, but thin, as Eclipse's are: Eclipse's width when
     *       one was seen, else {@link #ECLIPSE_THIN_WIDTH}. Removing it is no option: a
     *       {@code StyledText} without a caret shows none, so the user could not see where Eclipse
     *       types. Example: Detach Neovim on such an editor; it used to keep the block of normal
     *       mode.</li>
     *   <li>It shows another caret: Eclipse set it after the last change (Insert toggled overwrite
     *       mode, say), so it already has Eclipse's shape, and it stays. Putting the earlier caret
     *       back would give the editor a caret of the wrong mode, and disposing the current one
     *       would take it from Eclipse, which still holds it.</li>
     * </ul>
     */
    private void restoreOriginalCaret() {
        if (isWidgetDisposed()) {
            return;
        }
        Caret caret = styledText.getCaret();
        if (caret == null || caret.isDisposed()) {
            return;
        }
        if (caret == ownCaret) {
            if (eclipseCaret == null || eclipseCaret.isDisposed()) {
                // No caret of Eclipse's left to give back: this one stays the editor's caret.
                caret.setSize(eclipseWidth >= 0 ? eclipseWidth : ECLIPSE_THIN_WIDTH, styledText.getLineHeight());
                return;
            }
            styledText.setCaret(eclipseCaret);
            caret.dispose();
            ownCaret = null;
            caret = eclipseCaret;
        } else if (caret != eclipseCaret || eclipseWidth < 0) {
            return; // Eclipse's own, set since the last change
        }
        caret.setSize(eclipseWidth, styledText.getLineHeight());
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
        if (caret != null && !caret.isDisposed() && caret != eclipseCaret && caret != ownCaret) {
            // Eclipse's caret, seen for the first time: the one before Neovim, or one Eclipse made
            // since. Its width now is Eclipse's choice, which dispose() gives back.
            eclipseCaret = caret;
            eclipseWidth = caret.getSize().x;
        }
        if (caret == null || caret.isDisposed()) {
            caret = new Caret(styledText, SWT.NONE);
            styledText.setCaret(caret);
            ownCaret = caret;
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
