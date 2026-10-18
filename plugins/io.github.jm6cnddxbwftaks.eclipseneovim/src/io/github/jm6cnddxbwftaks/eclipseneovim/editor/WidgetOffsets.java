// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.Region;
import org.eclipse.swt.custom.StyledText;

/**
 * Converts between offsets in the editor's {@link StyledText} widget and offsets in its document.
 *
 * <p><b>Why they differ: folding.</b> A JFace viewer with folding (projection) shows the widget a
 * <em>projection</em> of the document: collapsed regions are left out. The Java editor collapses the
 * imports and the header comment by default, so line 30 of the file can be line 20 of the widget.
 * Neovim works on the whole document, so every caret or selection offset must be converted before it
 * meets a document position. {@link ITextViewerExtension5} is JFace's interface for that.
 *
 * <p>Example: with 10 lines of imports folded into one, {@code toModel(widget.getCaretOffset())}
 * on the widget's line 20 gives an offset on the document's line 29.
 *
 * <p>Viewers without that extension (no folding) map offsets one to one. UI thread only.
 */
final class WidgetOffsets {

    private final StyledText widget;
    /** {@code null} when the viewer cannot fold: offsets are then the same in widget and document. */
    private final ITextViewerExtension5 projection;

    WidgetOffsets(StyledText widget, ITextViewer viewer) {
        this.widget = widget;
        this.projection = viewer instanceof ITextViewerExtension5 extension ? extension : null;
    }

    /** The caret as a document offset. */
    int caretModelOffset() {
        return toModel(widget.getCaretOffset());
    }

    /** A widget offset as a document offset; offsets outside the widget map to the nearest document offset. */
    int toModel(int widgetOffset) {
        if (projection == null) {
            return widgetOffset;
        }
        int model = projection.widgetOffset2ModelOffset(widgetOffset);
        return model >= 0 ? model : widgetOffset;
    }

    /**
     * A document offset as a widget offset, unfolding the region that hides it, as Eclipse does when
     * it jumps into folded code.
     *
     * @return the widget offset, or -1 if the offset stays hidden (the viewer refused to unfold it)
     */
    int toWidgetRevealing(int modelOffset) {
        if (projection == null) {
            return modelOffset;
        }
        int offset = projection.modelOffset2WidgetOffset(modelOffset);
        if (offset < 0) {
            projection.exposeModelRange(new Region(modelOffset, 0));
            offset = projection.modelOffset2WidgetOffset(modelOffset);
        }
        return offset;
    }

    /** A document offset as a widget offset without unfolding anything; -1 if it is folded away. */
    int toWidget(int modelOffset) {
        return projection == null ? modelOffset : projection.modelOffset2WidgetOffset(modelOffset);
    }
}
