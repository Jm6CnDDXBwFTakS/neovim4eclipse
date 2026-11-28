// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Caret;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.IME;
import org.eclipse.swt.widgets.Listener;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.LineComposition;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PlatformUtil;

/**
 * An input method's composition (Japanese, Chinese, Korean) while Neovim's cursor is in a window it
 * draws itself: its command line, or a plug-in's float (a picker's prompt). Shown at that cursor,
 * and handed to Neovim at its commit, not written into the document. The decisions are
 * {@link LineComposition}'s; this is the SWT side.
 *
 * <h2>Why a filter</h2>
 * Neovim draws its command line itself (ui2), over the editor's last row, and the editor's text
 * widget keeps the keyboard, so the input method composes in the {@link StyledText}, which would
 * put the composing text into the document. While such a window has the cursor, a display filter takes the
 * text widget's {@code ImeComposition} events before the widget sees them (a filter runs before any
 * listener, and an event whose type it clears goes no further):
 * <ul>
 *   <li>{@code COMPOSITION_CHANGED}: the composing text is drawn in a small box at the line's
 *       cursor; at the commit the committed text goes to Neovim (on the line as a paste, in a
 *       float as typed keys: the owner's {@code commit}), and the key presses that repeat it are dropped by a second filter (measured on GTK, Windows and macOS:
 *       every system delivers the commit as key presses too). The event is taken from the widget
 *       ({@code type} cleared) but not refused ({@code doit} stays true): on macOS a refused
 *       commit tells Cocoa no key input happened, and Cocoa then sends the raw key that ended the
 *       composition, the Enter, which would run the line (SWT's Cocoa {@code IME.insertText},
 *       {@code Composite.keyDown}).</li>
 *   <li>The candidate list: every system places it at the widget's {@link Caret} (Cocoa's
 *       {@code firstRectForCharacterRange}, GTK's {@code gtk_im_context_set_cursor_location},
 *       Windows' {@code ImmSetCandidateWindow}), not where the plug-in answers
 *       {@code COMPOSITION_OFFSET}. So while a composition goes on, the widget's caret is moved to
 *       the line's cursor, and put back when the line closes: on the command line Eclipse's caret,
 *       in a float the stand-in {@code CursorStyleController} puts there while the composition
 *       goes on (a float has no caret otherwise, and the list opened elsewhere).</li>
 *   <li>{@code COMPOSITION_SELECTION}: no selection. macOS asks for it, and SWT 3.128's Cocoa side
 *       throws without an answer.</li>
 * </ul>
 *
 * <h2>Ending a composition</h2>
 * The line closing, a click or the focus leaving end it, and nothing is sent. On Linux and Windows
 * the input method ends it itself. On macOS it would not (measured: a click or the focus leaving
 * never ends a composition), and SWT has no call for it: Cocoa's {@code discardMarkedText} is called
 * through SWT's internal classes, by reflection, so a later SWT without them only leaves the
 * composition open, as before. Until SWT has a public call for it.
 *
 * <p>UI thread only.
 */
final class LineInputMethod {

    private static final Logger LOG = Logger.getLogger(LineInputMethod.class.getName());

    private final StyledText widget;
    private final Supplier<Rectangle> cursorCell;
    private final Consumer<String> commit;
    private final Consumer<Boolean> composingCaret;
    private final LineComposition composition = new LineComposition();
    private final Listener imeFilter = this::onIme;
    private final Listener keyFilter = this::onKey;
    private final Listener endFilter = this::onEnd;
    /** The composing text, drawn over the line; {@code null} until the first composition. */
    private Canvas box;
    private boolean attached;
    /** Where the editor's caret was before a composition moved it to the line, or {@code null}. */
    private Point caretBefore;
    /** The caret {@link #caretBefore} belongs to: only that one is put back there. */
    private Caret caretMoved;

    /**
     * @param widget     the editor's text widget, which keeps the keyboard meanwhile
     * @param cursorCell where Neovim's cursor is in the drawn window, in the widget's coordinates, or
     *                   {@code null} while not known ({@link FloatingWindows#cursorCell})
     * @param commit     hands committed text to Neovim
     * @param composingCaret told when a composition begins ({@code true}) and ends, before the caret
     *                   is placed: while a float has the keys the widget has no caret, and
     *                   {@code CursorStyleController.composing} gives it one meanwhile
     */
    LineInputMethod(StyledText widget, Supplier<Rectangle> cursorCell, Consumer<String> commit, Consumer<Boolean> composingCaret) {
        this.widget = widget;
        this.cursorCell = cursorCell;
        this.commit = commit;
        this.composingCaret = composingCaret;
    }

    /** Without a caret of its own for a float (the widget always has one): for tests of the command line. */
    LineInputMethod(StyledText widget, Supplier<Rectangle> cursorCell, Consumer<String> commit) {
        this(widget, cursorCell, commit, on -> { });
    }

    /**
     * Neovim's cursor went into a window it draws ({@code true}: a command line opened, a plug-in's
     * float entered) or out of the last one: the filters go on or off.
     */
    void lineOpen(boolean open) {
        if (open == attached || widget.isDisposed()) {
            return;
        }
        Display display = widget.getDisplay();
        if (open) {
            display.addFilter(SWT.ImeComposition, imeFilter);
            display.addFilter(SWT.KeyDown, keyFilter);
            display.addFilter(SWT.MouseDown, endFilter);
            widget.addListener(SWT.FocusOut, endFilter);
        } else {
            end();
            restoreCaret();
            display.removeFilter(SWT.ImeComposition, imeFilter);
            display.removeFilter(SWT.KeyDown, keyFilter);
            display.removeFilter(SWT.MouseDown, endFilter);
            widget.removeListener(SWT.FocusOut, endFilter);
        }
        attached = open;
    }

    void dispose() {
        lineOpen(false);
        if (box != null && !box.isDisposed()) {
            box.dispose();
        }
    }

    private boolean ours(Event e) {
        return !widget.isDisposed() && (e.widget == widget || e.widget == widget.getIME());
    }

    private void onIme(Event e) {
        if (!ours(e)) {
            return;
        }
        switch (e.detail) {
            case SWT.COMPOSITION_SELECTION -> {
                e.text = "";
                e.start = 0;
                e.end = 0;
            }
            case SWT.COMPOSITION_OFFSET -> {
                Rectangle cell = cursorCell.get();
                if (cell != null) {
                    e.x = cell.x;
                    e.y = cell.y;
                    e.width = cell.width;
                    e.height = cell.height;
                }
                e.index = 0;
            }
            case SWT.COMPOSITION_CHANGED -> {
                IME ime = widget.getIME();
                // The input method's whole text and its committed part are the IME object's;
                // an event without one (a test's) carries its text in the event.
                String text = ime != null && !ime.getText().isEmpty() ? ime.getText() : e.text;
                composed(text, ime != null ? ime.getCommitCount() : 0);
            }
            default -> {
                return; // another kind: the widget's
            }
        }
        e.type = SWT.None; // the text widget does not see it, so the document is not touched
    }

    /** The composition is now {@code text}, its first {@code committed} characters committed. Also for tests. */
    void composed(String text, int committed) {
        if (!text.isEmpty() && cursorCell.get() != null) {
            // A caret to place, also in a float; only with a cell to put it at, or the stand-in
            // would stay where StyledText put it, in the text, with no box over it.
            composingCaret.accept(true);
        }
        placeCaret();
        String sent = composition.changed(text, committed);
        if (!sent.isEmpty()) {
            commit.accept(sent);
        }
        showComposing();
        if (composition.composing().isEmpty()) {
            composingCaret.accept(false); // committed or cleared: nothing for a list to stand at
        }
    }

    /** The composing text's box, for tests; {@code null} before the first composition. */
    Canvas boxForTests() {
        return box;
    }

    private void onKey(Event e) {
        if (e.widget == widget && composition.dropKey(e.character)) {
            e.type = SWT.None; // the commit's echo: sent already, as text
            e.doit = false;
        }
    }

    /** A click anywhere, or the widget losing the focus: a composition still going on ends, unsent. */
    private void onEnd(Event e) {
        if (!composition.composing().isEmpty()) {
            end();
        }
    }

    private void end() {
        boolean was = !composition.composing().isEmpty();
        composition.end();
        showComposing();
        composingCaret.accept(false);
        if (was && PlatformUtil.isMac()) {
            discardMarkedText(widget);
        }
    }

    /** Moves the editor's caret to the line's cursor, where the systems open the candidate list. */
    private void placeCaret() {
        Caret caret = widget.getCaret();
        Rectangle cell = cursorCell.get();
        if (caret == null || cell == null) {
            return;
        }
        if (caretBefore == null || caretMoved != caret) {
            caretBefore = caret.getLocation();
            caretMoved = caret;
        }
        caret.setLocation(cell.x, cell.y);
    }

    /** Puts the editor's caret back where it was before a composition moved it. */
    private void restoreCaret() {
        Caret caret = widget.getCaret();
        // Only the caret moved: after a composition in a float, the stand-in has made way for
        // Eclipse's caret, which StyledText places itself.
        if (caretBefore != null && caret != null && caret == caretMoved && !caret.isDisposed()) {
            caret.setLocation(caretBefore);
        }
        caretBefore = null;
        caretMoved = null;
    }

    /** Draws the composing text in a box over the line's cursor, or hides the box when there is none. */
    private void showComposing() {
        String text = composition.composing();
        Rectangle cell = cursorCell.get();
        if (text.isEmpty() || cell == null) {
            if (box != null && !box.isDisposed()) {
                box.setVisible(false);
            }
            return;
        }
        if (box == null || box.isDisposed()) {
            box = new Canvas(widget, SWT.NO_FOCUS | SWT.NO_BACKGROUND);
            box.addListener(SWT.Paint, this::paintBox);
        }
        GC gc = new GC(widget);
        Point size;
        try {
            gc.setFont(widget.getFont());
            size = gc.textExtent(text);
        } finally {
            gc.dispose();
        }
        box.setBounds(cell.x, cell.y, size.x + 2, Math.max(size.y, cell.height));
        box.moveAbove(null);
        box.setVisible(true);
        box.redraw();
    }

    /** The composing text, in the editor's colors and font, underlined as input methods mark it. */
    private void paintBox(Event e) {
        Rectangle area = box.getClientArea();
        e.gc.setBackground(widget.getBackground());
        e.gc.fillRectangle(area);
        e.gc.setForeground(widget.getForeground());
        e.gc.setFont(widget.getFont());
        String text = composition.composing();
        e.gc.drawString(text, 1, 0, true);
        int baseline = area.height - 1;
        e.gc.drawLine(1, baseline, area.width - 1, baseline);
    }

    /**
     * macOS: ends the input method's composition on {@code control} through Cocoa's
     * {@code [[view inputContext] discardMarkedText]}, by reflection on SWT's internal classes (see
     * the class comment). Does nothing, and logs at FINE, when they are not there.
     */
    static void discardMarkedText(Control control) {
        try {
            Object view = Control.class.getField("view").get(control);
            long id = view.getClass().getField("id").getLong(view);
            Class<?> os = Class.forName("org.eclipse.swt.internal.cocoa.OS");
            long inputContext = (long) os.getMethod("sel_registerName", String.class).invoke(null, "inputContext");
            long discard = (long) os.getMethod("sel_registerName", String.class).invoke(null, "discardMarkedText");
            java.lang.reflect.Method send = os.getMethod("objc_msgSend", long.class, long.class);
            long context = (long) send.invoke(null, id, inputContext);
            if (context != 0) {
                send.invoke(null, context, discard);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.log(Level.FINE, "the input method's composition could not be ended (Cocoa's discardMarkedText)", e);
        }
    }
}
