// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Event;

/**
 * {@link EditorCaret} on the editor's {@link StyledText}, converting between widget and document
 * offsets with {@link WidgetOffsets} (folded code).
 *
 * <p>A move listener hears two widget events: caret moves, and mouse presses. The press matters
 * because clicking where the caret already is moves nothing, yet Neovim's cursor may be elsewhere
 * (for example after Neovim moved it while Eclipse's caret stayed). UI thread only.
 *
 * <p><b>Telling Eclipse about a move.</b> {@code setCaretOffset} and {@code setSelection} called by
 * code send no {@code SWT.Selection} event, and the editor's key and mouse listeners have already
 * run by the time Neovim's answer arrives. So without help Eclipse never learns that the caret
 * moved: the status bar keeps showing the old position (for example {@code 1 : 1 : 0} with the
 * cursor on line 11), and Mark Occurrences and the Outline view's linking stay on the old place.
 * After each move this class therefore sends the {@code SWT.Selection} event that the widget sends
 * for a move made by the user. The editor's text viewer turns it into a selection change, which
 * updates all of those. See {@link #announceMove()}.
 *
 * <p><b>Folded code.</b> An offset the widget does not show (it is inside a collapsed fold) is
 * handled in one of two ways. When Neovim has that fold closed too ({@link #setFoldCaption}, from
 * {@link FoldMirror#captionOffset}), the caret goes to the fold's caption line and nothing unfolds:
 * Neovim's cursor anywhere in a closed fold stands "on the fold", as Vim shows it. Otherwise the
 * fold is opened, as Eclipse does when it jumps into folded code ({@link WidgetOffsets#toWidgetRevealing}):
 * that is the case without mirrored folds (the opt-out, or a region Neovim could not have).
 */
final class StyledTextCaret implements EditorCaret {

    private final StyledText widget;
    private final WidgetOffsets offsets;
    /** The SWT listeners registered for each move listener, so that they can be removed again. */
    private final Map<Runnable, Object[]> registered = new IdentityHashMap<>();
    /** Document offset to the caption offset of the closed fold hiding it, or -1 (see the class comment). */
    private IntUnaryOperator foldCaption = offset -> -1;

    StyledTextCaret(StyledText widget, WidgetOffsets offsets) {
        this.widget = widget;
        this.offsets = offsets;
    }

    /** See the class comment, <em>Folded code</em>. UI thread. */
    void setFoldCaption(IntUnaryOperator foldCaption) {
        this.foldCaption = foldCaption != null ? foldCaption : offset -> -1;
    }

    /**
     * The widget offset to show {@code modelOffset} at: itself when shown; inside a fold Neovim has
     * closed, the start of the fold's caption line; else revealed by unfolding. -1 if it stays hidden.
     */
    private int widgetOffsetFor(int modelOffset) {
        int shown = offsets.toWidget(modelOffset);
        if (shown >= 0) {
            return shown;
        }
        int caption = foldCaption.applyAsInt(modelOffset);
        if (caption >= 0) {
            int atCaption = offsets.toWidget(caption);
            if (atCaption >= 0) {
                return atCaption;
            }
        }
        return offsets.toWidgetRevealing(modelOffset);
    }

    @Override
    public boolean isDisposed() {
        return widget.isDisposed();
    }

    @Override
    public int caretOffset() {
        return offsets.caretModelOffset();
    }

    @Override
    public int selectionStart() {
        return widget.getSelectionCount() > 0 ? offsets.toModel(widget.getSelectionRange().x) : caretOffset();
    }

    @Override
    public int selectionLength() {
        if (widget.getSelectionCount() == 0) {
            return 0;
        }
        Point range = widget.getSelectionRange(); // x = start, y = length, in widget offsets
        return offsets.toModel(range.x + range.y) - offsets.toModel(range.x);
    }

    @Override
    public boolean moveCaret(int offset) {
        int widgetOffset = widgetOffsetFor(offset); // the caption of a closed fold, or unfolds the code
        if (widgetOffset < 0) {
            return false;
        }
        widget.setCaretOffset(widgetOffset);
        widget.showSelection();
        announceMove();
        return true;
    }

    @Override
    public boolean select(int anchor, int caret) {
        int widgetAnchor = widgetOffsetFor(anchor);
        int widgetCaret = widgetOffsetFor(caret);
        if (widgetAnchor < 0 || widgetCaret < 0) {
            return false;
        }
        // StyledText puts the caret at its second argument, also when that is the smaller one (a
        // selection made leftward): setSelection(8, 1) selects 1..8 with the caret at 1.
        widget.setSelection(widgetAnchor, widgetCaret);
        widget.showSelection();
        announceMove();
        return true;
    }

    /**
     * Sends the {@code SWT.Selection} event that {@link StyledText} sends when the user changes the
     * selection: {@code x} and {@code y} are the start and end of the selection in widget offsets,
     * both the caret offset when nothing is selected. JFace's {@code TextViewer} listens for it and
     * fires a selection change (offset {@code x}, length {@code y - x}), which the editor uses to
     * update the position in the status bar.
     */
    private void announceMove() {
        Point selection = widget.getSelection(); // x = start, y = end
        Event event = new Event();
        event.x = selection.x;
        event.y = selection.y;
        widget.notifyListeners(SWT.Selection, event);
    }

    @Override
    public void addMoveListener(Runnable onMove) {
        CaretListener caretListener = e -> onMove.run();
        MouseListener mouseListener = new MouseAdapter() {
            @Override
            public void mouseDown(MouseEvent e) {
                onMove.run();
            }
        };
        widget.addCaretListener(caretListener);
        widget.addMouseListener(mouseListener);
        registered.put(onMove, new Object[] { caretListener, mouseListener });
    }

    @Override
    public void removeMoveListener(Runnable onMove) {
        Object[] listeners = registered.remove(onMove);
        if (listeners != null && !widget.isDisposed()) {
            widget.removeCaretListener((CaretListener) listeners[0]);
            widget.removeMouseListener((MouseListener) listeners[1]);
        }
    }
}
