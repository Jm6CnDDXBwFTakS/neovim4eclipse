// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.IdentityHashMap;
import java.util.Map;

import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.graphics.Point;

/**
 * {@link EditorCaret} on the editor's {@link StyledText}, converting between widget and document
 * offsets with {@link WidgetOffsets} (folded code).
 *
 * <p>A move listener hears two widget events: caret moves, and mouse presses. The press matters
 * because clicking where the caret already is moves nothing, yet Neovim's cursor may be elsewhere
 * (for example after Neovim moved it while Eclipse's caret stayed). UI thread only.
 */
final class StyledTextCaret implements EditorCaret {

    private final StyledText widget;
    private final WidgetOffsets offsets;
    /** The SWT listeners registered for each move listener, so that they can be removed again. */
    private final Map<Runnable, Object[]> registered = new IdentityHashMap<>();

    StyledTextCaret(StyledText widget, WidgetOffsets offsets) {
        this.widget = widget;
        this.offsets = offsets;
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
        int widgetOffset = offsets.toWidgetRevealing(offset); // unfolds code the cursor moved into
        if (widgetOffset < 0) {
            return false;
        }
        widget.setCaretOffset(widgetOffset);
        widget.showSelection();
        return true;
    }

    @Override
    public boolean select(int anchor, int caret) {
        int widgetAnchor = offsets.toWidgetRevealing(anchor);
        int widgetCaret = offsets.toWidgetRevealing(caret);
        if (widgetAnchor < 0 || widgetCaret < 0) {
            return false;
        }
        // StyledText puts the caret at its second argument, also when that is the smaller one (a
        // selection made leftward): setSelection(8, 1) selects 1..8 with the caret at 1.
        widget.setSelection(widgetAnchor, widgetCaret);
        widget.showSelection();
        return true;
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
