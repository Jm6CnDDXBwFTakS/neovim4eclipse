// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * Keeps Neovim's window the size of the editor's text area, and Neovim's {@code 'wrap'} the same as
 * the editor's word wrap, in both directions (docs/design/VIEWPORT.md, 3.1 and D2). One per attached
 * editor; created only when the viewport is on ({@code vim.g.neovim4eclipse_viewport}, D5).
 *
 * <p><b>Size.</b> The text area is measured in cells: its width over the font's average character
 * width, its height over the line height ({@link #cells}). Example: 720 × 520 pixels of a font
 * 9 pixels wide and 13 high are 80 columns and 40 rows. The size is sent when it changes, which
 * catches a resized window or part, a new font (Ctrl+= zooms it) and word wrap toggled on or off
 * (the horizontal scroll bar comes or goes): both a {@code Resize} and every {@code Paint} measure,
 * which is cheap, since the character width is read once per font. The session adds what Neovim
 * draws besides the window (status line, number column) and resizes Neovim's screen.
 *
 * <p><b>Word wrap.</b> Eclipse's wins on attach ({@link #start}). Alt+Shift+Y in Eclipse is seen at
 * the next paint ({@code StyledText.getWordWrap()} changed) and sent to Neovim; a {@code :set wrap}
 * in Neovim comes back through {@link #onNeovimWrap}, which sets the editor's word wrap. Neither is
 * echoed: the value last agreed on is kept, and only a difference from it is sent. An editor that
 * cannot wrap (block selection on, or no support) keeps its state, and Neovim is told so.
 *
 * <p>Why widget events and not Eclipse's word-wrap command: the widget is the one place every change
 * shows (the key, the menu, the toolbar button, {@code setWordWrap} from code), and the class needs
 * no workbench, so it is tested in {@code tests/swt}.
 *
 * <p>Not measured: lines made taller by code minings (Java editors' reference counts). Neovim then
 * believes in a row or two more than the editor shows there; {@code L} lands a line or two low.
 * UI thread only.
 */
final class ViewportFollower {

    private final StyledText text;
    private final BiConsumer<Integer, Integer> sendSize;
    private final Consumer<Boolean> sendWrap;
    private final Consumer<Boolean> setEditorWrap;
    private final Listener listener = this::onWidgetEvent;

    /** The font {@link #charWidth} was measured with; a new font is measured again. */
    private Font measuredFont;
    private double charWidth;
    /** The cells last sent, {@code {columns, rows}}; {@code null} before the first. */
    private int[] sent;
    /** The word wrap Eclipse and Neovim last agreed on. */
    private boolean wrap;
    private boolean started;

    /**
     * @param sendSize      receives the text area's columns and rows ({@code session::setEditorSizeAsync})
     * @param sendWrap      receives the editor's word wrap for Neovim
     * @param setEditorWrap sets the editor's word wrap ({@code ITextEditorExtension6.setWordWrap})
     */
    ViewportFollower(StyledText text, BiConsumer<Integer, Integer> sendSize, Consumer<Boolean> sendWrap,
            Consumer<Boolean> setEditorWrap) {
        this.text = text;
        this.sendSize = sendSize;
        this.sendWrap = sendWrap;
        this.setEditorWrap = setEditorWrap;
    }

    /**
     * Sends the editor's word wrap and size, then follows them. Call after the document push is
     * queued, so that Neovim's window shows this editor's buffer when the wrap arrives.
     */
    void start() {
        if (started || text.isDisposed()) {
            return;
        }
        started = true;
        wrap = text.getWordWrap();
        sendWrap.accept(wrap);
        update();
        text.addListener(SWT.Resize, listener);
        text.addListener(SWT.Paint, listener);
    }

    /**
     * Neovim's {@code 'wrap'} changed ({@code :set wrap}): the editor's word wrap follows. If the
     * editor cannot wrap now, Neovim gets the editor's state back.
     */
    void onNeovimWrap(boolean neovimWrap) {
        if (!started || text.isDisposed() || neovimWrap == wrap) {
            return;
        }
        // Agreed first: setting the editor's word wrap resizes and repaints the widget at once, and
        // the event listener must not take that for Eclipse's own change and send it back.
        wrap = neovimWrap;
        setEditorWrap.accept(neovimWrap);
        wrap = text.getWordWrap();
        if (wrap != neovimWrap) {
            sendWrap.accept(wrap); // block selection mode, or an editor without word wrap
        }
        update(); // the horizontal scroll bar came or went
    }

    void close() {
        if (!text.isDisposed()) {
            text.removeListener(SWT.Resize, listener);
            text.removeListener(SWT.Paint, listener);
        }
        started = false;
    }

    private void onWidgetEvent(Event event) {
        if (text.isDisposed()) {
            return;
        }
        if (text.getWordWrap() != wrap) {
            wrap = text.getWordWrap(); // Alt+Shift+Y, the menu, the toolbar
            sendWrap.accept(wrap);
        }
        update();
    }

    /** Measures the text area and sends its size if it changed. */
    private void update() {
        Font font = text.getFont();
        if (font != measuredFont) {
            measuredFont = font;
            charWidth = averageCharWidth(text);
        }
        int[] cells = cells(text, charWidth);
        if (sent == null || sent[0] != cells[0] || sent[1] != cells[1]) {
            sent = cells;
            sendSize.accept(cells[0], cells[1]);
        }
    }

    /**
     * The text area of {@code text} in cells, {@code {columns, rows}}: whole characters of
     * {@code charWidth} pixels and whole lines (a partly visible last line does not count, as in Vim),
     * inside the widget's margins. At least 1 × 1.
     */
    static int[] cells(StyledText text, double charWidth) {
        Rectangle area = text.getClientArea();
        int width = area.width - text.getLeftMargin() - text.getRightMargin();
        int height = area.height - text.getTopMargin() - text.getBottomMargin();
        int columns = charWidth > 0 ? (int) Math.floor(width / charWidth) : 1;
        int lineHeight = text.getLineHeight();
        int rows = lineHeight > 0 ? height / lineHeight : 1;
        return new int[] { Math.max(1, columns), Math.max(1, rows) };
    }

    /** The average character width of the widget's font, in pixels (for a monospace font, every one). */
    static double averageCharWidth(StyledText text) {
        GC gc = new GC(text);
        try {
            gc.setFont(text.getFont());
            return gc.getFontMetrics().getAverageCharacterWidth();
        } finally {
            gc.dispose();
        }
    }
}
