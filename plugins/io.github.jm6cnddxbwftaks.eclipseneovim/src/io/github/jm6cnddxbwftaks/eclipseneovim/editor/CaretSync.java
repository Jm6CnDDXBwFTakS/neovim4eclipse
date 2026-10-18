// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.widgets.Display;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.CursorPos;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.NeovimSession;

/**
 * Keeps the editor's caret and Neovim's cursor in the same place, in both directions:
 * <ul>
 *   <li><b>Neovim to Eclipse</b> ({@link #showNeovimCursor}): after {@code j}, {@code w},
 *       {@code gg} ... the caret moves to where Neovim put the cursor.</li>
 *   <li><b>Eclipse to Neovim</b>: a mouse click or any other caret move in the widget moves
 *       Neovim's cursor (to the start of the selection when Eclipse selected text).</li>
 * </ul>
 *
 * <p><b>The echo problem.</b> Moving the caret from Neovim's side fires the widget's caret listener,
 * which would send the same position straight back to Neovim (and, while Neovim is mid-command,
 * could move its cursor at the wrong moment). So every caret change that comes from Neovim runs
 * inside {@link #runAsFromNeovim}, and the listener ignores changes made while it runs. The same goes
 * for document rewrites applied from Neovim ({@code IDocument.set} resets the caret to 0): those are
 * recognized by {@code applyingRemoteChange}.
 *
 * <p>Neovim columns are UTF-8 bytes, Eclipse columns UTF-16 chars; {@link DocumentPositions}
 * converts between them. The widget's caret offset is first turned into a document offset
 * ({@link WidgetOffsets}), because folded code is missing from the widget. Created and disposed on
 * the UI thread.
 */
final class CaretSync {

    private static final Logger LOG = Logger.getLogger(CaretSync.class.getName());

    private final StyledText widget;
    private final NeovimSession session;
    /** The editor's current document (may be {@code null}). */
    private final Supplier<IDocument> document;
    private final WidgetOffsets offsets;
    /** Whether caret moves in Eclipse should reach Neovim (Neovim mode on). */
    private final BooleanSupplier followEclipseCaret;
    /** True while the synchronizer applies a change from Neovim to the document. */
    private final BooleanSupplier applyingRemoteChange;

    private volatile boolean disposed;
    /** Set while a caret change from Neovim is applied, so the listener does not echo it back. */
    private volatile boolean applyingFromNeovim;

    private final CaretListener caretListener = e -> onEclipseCaretMoved();
    private final MouseListener mouseListener = new MouseAdapter() {
        @Override
        public void mouseDown(MouseEvent e) {
            onEclipseCaretMoved();
        }
    };

    CaretSync(StyledText widget, WidgetOffsets offsets, NeovimSession session, Supplier<IDocument> document,
            BooleanSupplier followEclipseCaret, BooleanSupplier applyingRemoteChange) {
        this.widget = widget;
        this.offsets = offsets;
        this.session = session;
        this.document = document;
        this.followEclipseCaret = followEclipseCaret;
        this.applyingRemoteChange = applyingRemoteChange;
        widget.addCaretListener(caretListener);
        widget.addMouseListener(mouseListener);
    }

    /**
     * Moves the caret to Neovim's cursor. Any thread: applied at once on the UI thread (so nothing
     * can interleave after a document edit and leave the caret at 0), else posted to it.
     */
    void showNeovimCursor(CursorPos pos) {
        if (pos == null || disposed) {
            return;
        }
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        if (display.getThread() == Thread.currentThread()) {
            applyOnUi(pos);
        } else {
            display.asyncExec(() -> applyOnUi(pos));
        }
    }

    private void applyOnUi(CursorPos pos) {
        if (disposed || widget.isDisposed()) {
            return;
        }
        runAsFromNeovim(() -> {
            try {
                IDocument doc = document.get();
                if (doc == null || doc.getNumberOfLines() <= 0) {
                    return;
                }
                int offset = Math.min(DocumentPositions.byteColToOffset(doc, pos.line1Based(), pos.col0Based()),
                        doc.getLength());
                int widgetOffset = offsets.toWidgetRevealing(offset); // unfolds code the cursor moved into
                if (widgetOffset >= 0) {
                    widget.setCaretOffset(widgetOffset);
                    widget.showSelection();
                }
            } catch (Exception e) {
                LOG.log(Level.FINE, "Cursor apply failed", e);
            }
        });
    }

    /** Runs a caret or selection change that comes from Neovim, so that it is not echoed back. UI thread. */
    void runAsFromNeovim(Runnable change) {
        applyingFromNeovim = true;
        try {
            change.run();
        } finally {
            applyingFromNeovim = false;
        }
    }

    /** The user clicked or moved the caret in Eclipse: push the position to Neovim. */
    private void onEclipseCaretMoved() {
        if (disposed || applyingFromNeovim || widget.isDisposed() || !followEclipseCaret.getAsBoolean()
                || applyingRemoteChange.getAsBoolean()) {
            return;
        }
        try {
            int[] lineCol = DocumentPositions.offsetToLineCol(document.get(), cursorModelOffset());
            session.setCursorAsync(lineCol[0], lineCol[1]);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Eclipse→Neovim cursor failed", e);
        }
    }

    /**
     * Where Neovim's cursor belongs, as a document offset: the start of Eclipse's selection when
     * there is one, else the caret. Eclipse's undo selects the text it brings back, with the caret
     * after it (on the next line when a whole line comes back), while Vim's {@code u} puts the
     * cursor on the restored line. UI thread.
     */
    private int cursorModelOffset() {
        return widget.getSelectionCount() > 0 ? offsets.toModel(widget.getSelectionRange().x)
                : offsets.caretModelOffset();
    }

    /**
     * Moves Neovim's cursor to {@link #cursorModelOffset()} now: a push of Eclipse's change can move
     * Neovim's cursor (lines inserted above it push it down) without moving Eclipse's caret.
     * Blocking: the session's input thread only. The caret is read on the UI thread, which waits
     * until Eclipse has finished what it is doing (an undo changes the document first and selects
     * after).
     */
    void sendEclipseCaretNow() {
        Display display = Display.getDefault();
        if (disposed || display == null || display.isDisposed()) {
            return;
        }
        int[][] lineCol = new int[1][];
        display.syncExec(() -> {
            IDocument doc = document.get();
            if (!disposed && !widget.isDisposed() && doc != null) {
                lineCol[0] = DocumentPositions.offsetToLineCol(doc, cursorModelOffset());
            }
        });
        if (lineCol[0] == null) {
            return;
        }
        try {
            session.setCursor(lineCol[0][0], lineCol[0][1]);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Eclipse→Neovim cursor after a push failed", e);
        }
    }

    /** Stops both directions. UI thread. */
    void dispose() {
        disposed = true;
        if (!widget.isDisposed()) {
            widget.removeCaretListener(caretListener);
            widget.removeMouseListener(mouseListener);
        }
    }
}
