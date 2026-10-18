// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.AnnotationModel;
import org.eclipse.jface.text.source.projection.ProjectionAnnotation;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link WidgetOffsets} with folding, as in the Java editor: lines "b" to "d" form a folded region,
 * so the widget shows only its first line, "b", and leaves "c" and "d" out.
 */
class WidgetOffsetsTest {

    //                                           a=0   b=2   c=4   d=6   e=8
    private final Document document = new Document("a\nb\nc\nd\ne\n");
    private Shell shell;
    private StyledText widget;
    private WidgetOffsets offsets;
    private ProjectionAnnotation fold;
    private ProjectionViewer viewer;

    @BeforeEach
    void foldLinesBToD() {
        shell = new Shell(Display.getCurrent());
        viewer = new ProjectionViewer(shell, null, null, false, SWT.NONE);
        viewer.setDocument(document, new AnnotationModel());
        viewer.enableProjection();
        fold = new ProjectionAnnotation();
        viewer.getProjectionAnnotationModel().addAnnotation(fold, new Position(2, 6)); // "b\nc\nd\n"
        viewer.getProjectionAnnotationModel().collapse(fold);
        widget = viewer.getTextWidget();
        offsets = new WidgetOffsets(widget, viewer);
    }

    @AfterEach
    void dispose() {
        shell.dispose();
    }

    @Test
    void theWidgetLeavesTheFoldedLinesOut() {
        assertEquals("a\nb\ne\n", widget.getText());
    }

    @Test
    void aCaretBelowTheFoldIsOnTheRightDocumentLine() {
        widget.setCaretOffset(4); // "e" in the widget
        assertEquals(8, offsets.caretModelOffset()); // "e" in the document
    }

    @Test
    void documentOffsetsMapToTheWidgetAndHiddenOnesAreReported() {
        assertEquals(4, offsets.toWidget(8));
        assertEquals(-1, offsets.toWidget(4)); // "c" is folded away
    }

    @Test
    void revealingAHiddenOffsetUnfoldsIt() {
        int widgetOffset = offsets.toWidgetRevealing(4);
        assertTrue(widgetOffset >= 0, "offset should be visible after unfolding");
        assertFalse(fold.isCollapsed());
        assertEquals("c", widget.getText(widgetOffset, widgetOffset));
    }

    @Test
    void withoutFoldingOffsetsAreTheSame() {
        WidgetOffsets plain = new WidgetOffsets(widget, null);
        assertEquals(7, plain.toModel(7));
        assertEquals(7, plain.toWidgetRevealing(7));
    }
}
