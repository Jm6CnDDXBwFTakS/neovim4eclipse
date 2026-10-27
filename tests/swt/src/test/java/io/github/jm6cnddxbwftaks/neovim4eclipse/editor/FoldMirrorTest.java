// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.AnnotationModel;
import org.eclipse.jface.text.source.projection.IProjectionPosition;
import org.eclipse.jface.text.source.projection.ProjectionAnnotation;
import org.eclipse.jface.text.source.projection.ProjectionAnnotationModel;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.UiExecutor;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FoldRegions.Fold;

/**
 * {@link FoldMirror} on a real {@link ProjectionViewer}, as the Java editor uses one. The document has
 * nine lines {@code "l1"} to {@code "l9"}, three characters each, so line {@code n} starts at offset
 * {@code 3 * (n - 1)}. Folds: an outer one over lines 2-8 (index 0) with two inside, 3-4 (1) and 6-7
 * (2). What Neovim does with the list is {@code folds_spec.lua}.
 */
class FoldMirrorTest {

    private final IDocument document = new Document("l1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9\n");
    private final List<List<Fold>> sends = new ArrayList<>();
    private Shell shell;
    private ProjectionViewer viewer;
    private ProjectionAnnotationModel model;
    private ProjectionAnnotation outer;
    private ProjectionAnnotation first;
    private ProjectionAnnotation second;
    private FoldMirror mirror;

    private static int line(int n) {
        return 3 * (n - 1);
    }

    /** The position of lines {@code from} to {@code to}, as Eclipse makes them: up to the next line's start. */
    private static Position lines(int from, int to) {
        return new Position(line(from), line(to + 1) - line(from));
    }

    @BeforeEach
    void create() {
        shell = new Shell(Display.getDefault());
        viewer = new ProjectionViewer(shell, null, null, false, SWT.NONE);
        viewer.setDocument(document, new AnnotationModel());
        viewer.enableProjection();
        model = viewer.getProjectionAnnotationModel();
        outer = new ProjectionAnnotation();
        first = new ProjectionAnnotation();
        second = new ProjectionAnnotation();
        model.addAnnotation(outer, lines(2, 8));
        model.addAnnotation(first, lines(3, 4));
        model.addAnnotation(second, lines(6, 7));
        mirror = new FoldMirror(viewer, UiExecutor.IMMEDIATE, sends::add);
        mirror.send();
        sends.clear();
    }

    @AfterEach
    void dispose() {
        mirror.close();
        shell.dispose();
    }

    @Test
    void theRegionsAreSentAsLinesInNeovimsOrder() {
        model.collapse(second);
        assertEquals(List.of(new Fold(2, 8, false), new Fold(3, 4, false), new Fold(6, 7, true)), last());
    }

    @Test
    void aFoldCollapsedInEclipseIsSentAgain() {
        model.collapse(first);
        assertFalse(sends.isEmpty(), "a collapse from the ruler reaches Neovim");
        assertTrue(last().get(1).collapsed());
    }

    @Test
    void neovimsStateIsAppliedAndNotSentBack() {
        mirror.applyNeovimState(List.of(1, 2)); // zc on both methods
        assertTrue(first.isCollapsed());
        assertTrue(second.isCollapsed());
        assertFalse(outer.isCollapsed());
        assertEquals(List.of(), sends, "no echo");

        mirror.applyNeovimState(List.of(2)); // zo on the first
        assertFalse(first.isCollapsed());
        assertTrue(second.isCollapsed());
    }

    @Test
    void foldsInsideAClosedOneKeepEclipsesState() {
        model.collapse(first);
        sends.clear();
        mirror.applyNeovimState(List.of(0)); // zM: Neovim reports the outer fold alone
        assertTrue(outer.isCollapsed());
        assertTrue(first.isCollapsed(), "kept");
        assertFalse(second.isCollapsed(), "kept");

        mirror.applyNeovimState(List.of()); // zR
        assertFalse(outer.isCollapsed());
        assertFalse(first.isCollapsed());
    }

    @Test
    void theCaptionOfAPlainPositionIsItsFirstLine() {
        model.collapse(first);
        assertEquals(line(3), mirror.captionOffset(line(4) + 1));
        assertEquals(-1, mirror.captionOffset(line(5)), "not in a collapsed fold");
    }

    @Test
    void theOutermostCollapsedFoldGivesTheCaption() {
        model.collapse(first);
        model.collapse(outer);
        assertEquals(line(2), mirror.captionOffset(line(4)));
    }

    @Test
    void aProjectionPositionsCaptionIsItsCaptionLine() {
        // As JDT does for a method with Javadoc: lines 2-3 the comment, line 4 the signature.
        ProjectionAnnotation method = new ProjectionAnnotation();
        model.removeAllAnnotations();
        model.addAnnotation(method, new CaptionPosition(line(2), line(7) - line(2), line(4) - line(2)));
        mirror.send();
        model.collapse(method);
        assertEquals(line(4), mirror.captionOffset(line(3)));
        assertEquals(List.of(new Fold(2, 6, true)), last());
    }

    @Test
    void turningProjectionOffSendsNoFoldsAndOnSendsThemAgain() {
        viewer.disableProjection();
        assertEquals(List.of(), last());
        assertEquals(-1, mirror.captionOffset(line(4)));
        viewer.enableProjection();
        // A new, empty model: the editor's folding provider fills it; here nothing does.
        viewer.getProjectionAnnotationModel().addAnnotation(new ProjectionAnnotation(), lines(5, 6));
        assertEquals(List.of(new Fold(5, 6, false)), last());
    }

    @Test
    void theCaretGoesToTheCaptionOfAClosedFoldWithoutUnfoldingIt() {
        model.collapse(first);
        StyledTextCaret caret = new StyledTextCaret(viewer.getTextWidget(), new WidgetOffsets(viewer.getTextWidget(), viewer));
        caret.setFoldCaption(mirror::captionOffset);

        assertTrue(caret.moveCaret(line(4) + 1)); // Neovim's cursor on line 4, inside the closed fold
        assertTrue(first.isCollapsed(), "still folded");
        assertEquals(line(3), caret.caretOffset());
    }

    @Test
    void withoutACaptionLookupTheCaretUnfoldsAsBefore() {
        model.collapse(first);
        StyledTextCaret caret = new StyledTextCaret(viewer.getTextWidget(), new WidgetOffsets(viewer.getTextWidget(), viewer));

        assertTrue(caret.moveCaret(line(4) + 1));
        assertFalse(first.isCollapsed());
        assertEquals(line(4) + 1, caret.caretOffset());
    }

    private List<Fold> last() {
        assertFalse(sends.isEmpty(), "nothing was sent");
        return sends.get(sends.size() - 1);
    }

    /** A position whose caption is not its first line, like JDT's for a member with a comment. */
    private static final class CaptionPosition extends Position implements IProjectionPosition {
        private final int captionOffset;

        CaptionPosition(int offset, int length, int captionOffset) {
            super(offset, length);
            this.captionOffset = captionOffset;
        }

        @Override
        public IRegion[] computeProjectionRegions(IDocument document) throws BadLocationException {
            // Hide everything but the caption line: the comment above it and the body below it.
            int captionLine = document.getLineOfOffset(offset + captionOffset);
            int captionStart = document.getLineOffset(captionLine);
            int afterCaption = captionStart + document.getLineLength(captionLine);
            return new IRegion[] {
                    new org.eclipse.jface.text.Region(offset, captionStart - offset),
                    new org.eclipse.jface.text.Region(afterCaption, offset + length - afterCaption) };
        }

        @Override
        public int computeCaptionOffset(IDocument document) {
            return captionOffset;
        }
    }
}
