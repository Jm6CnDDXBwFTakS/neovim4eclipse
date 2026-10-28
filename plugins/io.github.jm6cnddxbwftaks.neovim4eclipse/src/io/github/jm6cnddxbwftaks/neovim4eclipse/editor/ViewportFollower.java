// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * Keeps Neovim's window the size of the editor's text area, Neovim's {@code 'wrap'} the same as the
 * editor's word wrap, in both directions, and the editor scrolled to Neovim's top line
 * (docs/design/VIEWPORT.md, 3.1, 3.2 and D2). One per attached editor; created only when the
 * viewport is on ({@code vim.g.neovim4eclipse_viewport}, D5).
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
 * cannot wrap ({@code AbstractTextEditor.isWordWrapSupported()} false) keeps its state, and Neovim
 * is told so. Block selection is no obstacle: Eclipse turns it off when word wrap goes on.
 *
 * <p><b>Top line.</b> Neovim decides where its window is scrolled ({@code Ctrl-E}, {@code zz},
 * {@code 'scrolloff'}, a jump), and the editor follows ({@link #onNeovimTopLine}), before the caret
 * moves: the caret then lands on a visible line, and Eclipse's own reveal does not scroll. Example:
 * the editor and Neovim show lines 1 to 20, and {@code 50G} scrolls Neovim's window to line 40; the
 * editor shows 40 to 59, then the caret goes to line 50. Without this, Eclipse would show 31 to 50
 * (its reveal scrolls as little as it can), and {@code H} would go to line 40, off the screen. On
 * attach it is the other way round ({@link #start}): Neovim's window is scrolled to the editor's top
 * line, which Neovim may change to keep its cursor visible; that answer comes back as a top line.
 *
 * <p><b>Scrolling in Eclipse</b> (the scroll bar, the wheel, Eclipse revealing a search hit) is seen
 * at the next paint: a top line other than the one last agreed with Neovim is sent
 * ({@link Neovim#sendScroll}), and Neovim drags its cursor into the new view as {@code Ctrl-E} does.
 * Without this the next key would scroll the editor back to Neovim's old view. Not echoed: a top
 * line from Neovim is the agreed one once applied, and the paint it causes then sends nothing.
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

    /** What the follower sends to Neovim; the session's {@code ViewportPort} methods in the plug-in. */
    interface Neovim {
        /** The text area's size in cells ({@code ViewportPort.setEditorSizeAsync}). */
        void sendSize(int columns, int rows);

        /** The editor's word wrap ({@code ViewportPort.setWrapAsync}). */
        void sendWrap(boolean wrap);

        /** The editor's top line, 1-based, on attach: Neovim keeps its cursor ({@code ViewportPort.setTopLineAsync}). */
        void sendTopLine(int line1);

        /**
         * The user scrolled the editor to {@code line1}: Neovim drags its cursor along
         * ({@code ViewportPort.scrollToAsync}). Ignored while Eclipse types (Neovim's lines are
         * behind; Esc sends the view).
         */
        void sendScroll(int line1);
    }

    /**
     * The editor around the widget. Lines are the document's, 0-based: where code is folded, the
     * widget's own line numbers leave the hidden lines out, so the editor converts
     * ({@code ITextViewer.getTopIndex} and {@code setTopIndex} do).
     */
    interface Editor {
        /** Sets the editor's word wrap ({@code ITextEditorExtension6.setWordWrap}). */
        void setWordWrap(boolean wrap);

        /** The document line at the top of the editor, 0-based. */
        int topLine();

        /** Scrolls the editor so that document line {@code line0} is at its top. */
        void setTopLine(int line0);
    }

    private final StyledText text;
    private final Neovim neovim;
    private final Editor editor;
    private final Listener listener = this::onWidgetEvent;

    /** The font {@link #charWidth} was measured with; a new font is measured again. */
    private Font measuredFont;
    private double charWidth;
    /** The cells last sent, {@code {columns, rows}}; {@code null} before the first. */
    private int[] sent;
    /** The word wrap Eclipse and Neovim last agreed on. */
    private boolean wrap;
    private boolean started;
    /** The top line, 1-based, that Neovim and the editor last agreed on (sent or applied). */
    private int agreedTopLine;

    ViewportFollower(StyledText text, Neovim neovim, Editor editor) {
        this.text = text;
        this.neovim = neovim;
        this.editor = editor;
    }

    /**
     * Sends the editor's word wrap, size and top line, in that order (the top line Neovim can keep
     * depends on its window's height), then follows them. Call after the document push is queued, so
     * that Neovim's window shows this editor's buffer, with the cursor placed, when they arrive.
     */
    void start() {
        if (started || text.isDisposed()) {
            return;
        }
        started = true;
        wrap = text.getWordWrap();
        neovim.sendWrap(wrap);
        update();
        agreedTopLine = eclipseTopLine();
        neovim.sendTopLine(agreedTopLine);
        text.addListener(SWT.Resize, listener);
        text.addListener(SWT.Paint, listener);
    }

    /**
     * Neovim's window has {@code line1} (1-based) at its top now: the editor scrolls there, unless
     * it is there already (the same line often comes twice for one key). Call it as a change from
     * Neovim ({@code CaretSync.runAsFromNeovim}). UI thread.
     */
    void onNeovimTopLine(int line1) {
        if (!started || text.isDisposed() || line1 < 1) {
            return;
        }
        if (editor.topLine() != line1 - 1) {
            editor.setTopLine(line1 - 1);
        }
        // What the editor shows now, which may differ from line1 (a fold's caption, the end of the
        // document, which Eclipse cannot scroll past): not to be sent back as a scroll of the user's.
        agreedTopLine = eclipseTopLine();
    }

    /**
     * The editor's top line, 1-based: what Neovim's window is scrolled to on attach, and at Esc,
     * after Eclipse scrolled alone while it typed (decision D6). UI thread.
     */
    int eclipseTopLine() {
        return editor.topLine() + 1;
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
        editor.setWordWrap(neovimWrap);
        wrap = text.getWordWrap();
        if (wrap != neovimWrap) {
            neovim.sendWrap(wrap); // an editor without word wrap
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
            neovim.sendWrap(wrap);
        }
        update();
        if (event.type == SWT.Paint && eclipseTopLine() != agreedTopLine) {
            agreedTopLine = eclipseTopLine(); // the scroll bar, the wheel, a reveal
            neovim.sendScroll(agreedTopLine);
        }
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
            neovim.sendSize(cells[0], cells[1]);
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
