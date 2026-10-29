// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.source.AnnotationModel;
import org.eclipse.jface.text.source.projection.IProjectionPosition;
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
        shell = new Shell(Display.getDefault());
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

    /**
     * A fold whose caption is its second line, as the Java editor folds a header comment or a method
     * with Javadoc: its first line and the lines after the caption are hidden.
     */
    private static final class CaptionOnSecondLine extends Position implements IProjectionPosition {
        CaptionOnSecondLine(int offset, int length) {
            super(offset, length);
        }

        @Override
        public IRegion[] computeProjectionRegions(IDocument document) throws BadLocationException {
            int first = document.getLineOfOffset(offset);
            int captionEnd = document.getLineOffset(first + 2);
            return new IRegion[] {
                    new Region(offset, document.getLineOffset(first + 1) - offset),
                    new Region(captionEnd, offset + length - captionEnd) };
        }

        @Override
        public int computeCaptionOffset(IDocument document) throws BadLocationException {
            return document.getLineOffset(document.getLineOfOffset(offset) + 1) - offset;
        }
    }

    /**
     * Neovim's {@code O} on the first line of such a closed fold inserts a line where the hidden
     * first line starts. JFace keeps the new line hidden although the fold moved past it (see
     * WidgetOffsets, "Text hidden by no fold"): revealing it rebuilds the hidden ranges, shows the
     * new line and keeps the fold collapsed.
     */
    @Test
    void aLineInsertedAboveAFoldWhoseFirstLineIsHiddenIsRevealedAndTheFoldStaysCollapsed() throws Exception {
        viewer.getProjectionAnnotationModel().removeAllAnnotations();
        ProjectionAnnotation header = new ProjectionAnnotation();
        viewer.getProjectionAnnotationModel().addAnnotation(header, new CaptionOnSecondLine(0, 6)); // "a\nb\nc\n"
        viewer.getProjectionAnnotationModel().collapse(header);
        assertEquals("b\nd\ne\n", widget.getText(), "a and c hidden, b the caption");

        document.replace(0, 0, "x\n");
        assertEquals(-1, offsets.toWidget(0), "JFace hides the new line with a");

        int widgetOffset = offsets.toWidgetRevealing(0);
        assertTrue(widgetOffset >= 0, "the new line is shown");
        assertEquals("x", widget.getText(widgetOffset, widgetOffset));
        assertTrue(header.isCollapsed(), "the fold stays collapsed");
        assertEquals("x\nb\nd\ne\n", widget.getText());
    }

    @Test
    void withoutFoldingOffsetsAreTheSame() {
        WidgetOffsets plain = new WidgetOffsets(widget, null);
        assertEquals(7, plain.toModel(7));
        assertEquals(7, plain.toWidgetRevealing(7));
    }
}
