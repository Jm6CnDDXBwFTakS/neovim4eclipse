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
 * <p><b>Hidden while Neovim's cursor is elsewhere.</b> While Neovim's cursor is in a plug-in's
 * window drawn over the text (a picker's prompt, {@code FloatingWindows.hasFocusedFloat}), the
 * keys go there, not to the text, so the caret is hidden ({@link #setHidden}): one cursor shows
 * where they go. How: the widget's caret is taken off it ({@code setCaret(null)}; a
 * {@code StyledText} without a caret draws none) and put back after. Not {@code setVisible(false)}:
 * {@code StyledText} sets its caret's visibility itself, from where it is
 * ({@code updateCaretVisibility}), and SWT shows a caret again when the widget scrolls.
 *
 * <p><b>A stand-in while an input method composes.</b> Every system opens an input method's
 * candidate list at the widget's caret (GTK's {@code gtk_im_context_set_cursor_location}, set when
 * the caret moves; Cocoa's {@code firstRectForCharacterRange}, which answers an empty rectangle
 * without one; Windows' {@code ImmSetCandidateWindow}, at the system caret SWT's caret moves).
 * Without a caret, Japanese typed into a picker's prompt opened its candidates where the editor's
 * caret had been (GTK), or in a corner of the screen (macOS). So while a composition goes on in
 * such a window ({@link #composing}), the widget has a caret of this controller's own, the
 * stand-in, which {@code LineInputMethod} puts at Neovim's cursor; it is thin and lies under the
 * float and the composing text's box, which hide it, and it goes when the composition ends. It is never kept as
 * Eclipse's caret.
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
    /** Whether the caret is hidden ({@link #setHidden}); any thread sets it, the UI thread applies it. */
    private volatile boolean hidden;
    /** The caret taken off the widget while hidden, to be put back; {@code null} when none is. UI thread. */
    private Caret hiddenCaret;
    /**
     * The widget's caret while Eclipse's is hidden and an input method composes ({@link #composing}):
     * this controller's own; made the first time, disposed with the controller, never kept as
     * Eclipse's. UI thread.
     */
    private Caret standIn;

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
        Runnable restore = () -> {
            showHiddenCaret(); // a caret hidden for a float goes back first, then gets its width back
            restoreOriginalCaret();
            disposeStandIn();
        };
        if (display.getThread() == Thread.currentThread()) {
            restore.run();
        } else {
            display.asyncExec(restore);
        }
    }

    /**
     * Hides the caret ({@code true}) or shows it again: hidden while Neovim's cursor is in a
     * plug-in's window drawn over the text (see the class comment). Any thread; applied on the UI
     * thread in the order called. Example: a picker's prompt opens with the cursor in it: hidden;
     * the prompt closes, the cursor back in the editor's window: shown, in the shape of the mode
     * Neovim is in then.
     */
    public void setHidden(boolean hide) {
        if (disposed) {
            return;
        }
        hidden = hide;
        runOnUi(() -> {
            if (disposed || isWidgetDisposed()) {
                return;
            }
            if (hidden) {
                hideCaret();
            } else if (showHiddenCaret()) {
                apply(block); // the mode may have changed meanwhile
            }
        });
    }

    /** Whether the caret is hidden now; for tests. UI thread. */
    boolean isHidden() {
        return hiddenCaret != null;
    }

    /** Takes the widget's caret off it, keeping it to put back (never the stand-in). UI thread. */
    private void hideCaret() {
        Caret caret = styledText.getCaret();
        // Also when one is hidden already: Eclipse may have set a new caret meanwhile (the
        // overwrite toggle, a caret preference), which is then the one to keep; the old one is
        // Eclipse's to dispose. The stand-in itself is never kept as Eclipse's.
        if (caret != null && !caret.isDisposed() && caret != standIn) {
            hiddenCaret = caret;
            styledText.setCaret(null);
        }
    }

    /**
     * An input method's composition began ({@code true}) or ended in a window drawn over the text:
     * while Eclipse's caret is hidden, the widget has the stand-in meanwhile, for the systems to
     * place the candidate list at (see the class comment). Nothing changes while the caret is not
     * hidden: on the command line, Eclipse's own caret is the one moved. UI thread, at once (the
     * caller places the caret right after).
     */
    void composing(boolean on) {
        if (disposed || isWidgetDisposed()) {
            return;
        }
        Caret current = styledText.getCaret();
        if (on && hiddenCaret != null && current == null) {
            if (standIn == null || standIn.isDisposed()) {
                standIn = new Caret(styledText, SWT.NONE);
            }
            // On the widget first: GTK's Caret.setSize updates the widget's caret position for
            // the input method, and threw while the widget had none (the font zoomed between two
            // compositions changes the size).
            styledText.setCaret(standIn);
            standIn.setSize(ECLIPSE_THIN_WIDTH, styledText.getLineHeight());
            // Visible, whatever StyledText decided from Eclipse's caret position: Windows moves
            // the system caret, where its input method looks, only for a visible caret. It lies
            // under the float and the composing text's box, which hide it.
            standIn.setVisible(true);
        } else if (!on && current != null && current == standIn) {
            styledText.setCaret(null);
        }
    }

    /** Disposes the stand-in, taking it off the widget first if it is there (no caret to put back). UI thread. */
    private void disposeStandIn() {
        if (standIn == null || standIn.isDisposed()) {
            return;
        }
        if (!isWidgetDisposed() && styledText.getCaret() == standIn) {
            styledText.setCaret(null);
        }
        standIn.dispose();
        standIn = null;
    }

    /**
     * Puts the caret taken off by {@link #hideCaret} back, unless the widget got another one
     * meanwhile (Eclipse made a new one: it stays, and the kept one is Eclipse's old one, which it
     * may still dispose itself). Whether one was hidden. UI thread.
     */
    private boolean showHiddenCaret() {
        Caret caret = hiddenCaret;
        if (caret == null) {
            return false;
        }
        hiddenCaret = null;
        if (isWidgetDisposed()) {
            return true;
        }
        Caret current = styledText.getCaret();
        if (current == standIn) {
            // Off first: StyledText.setCaret places the carets it had before the new one, so from
            // the stand-in straight to Eclipse's it would move the stand-in, not Eclipse's caret.
            styledText.setCaret(null);
            current = null;
        }
        if (current == null) {
            // Eclipse's back; if Eclipse disposed it meanwhile, none (apply makes one).
            styledText.setCaret(caret.isDisposed() ? null : caret);
        }
        return true;
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
        if (isWidgetDisposed() || hiddenCaret != null) {
            return; // hidden: setHidden(false) applies the mode's shape when it puts the caret back
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
