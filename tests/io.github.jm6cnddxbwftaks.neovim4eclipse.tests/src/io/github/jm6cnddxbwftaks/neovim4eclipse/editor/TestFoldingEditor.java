// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.IVerticalRuler;
import org.eclipse.jface.text.source.projection.ProjectionAnnotation;
import org.eclipse.jface.text.source.projection.ProjectionAnnotationModel;
import org.eclipse.jface.text.source.projection.ProjectionSupport;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.editors.text.TextEditor;

/**
 * A text editor that folds, for the folding UI tests without Java tooling: registered for
 * {@code *.fold} files in {@code fragment.xml}. Like the Java editor it shows its text through a
 * {@link ProjectionViewer} with {@link ProjectionSupport} (the ruler's +/- and the folding
 * commands). Its folds are fixed: a line ending in <code>{</code> starts one that ends at the line
 * of its matching <code>}</code>. Example: in {@code "a {\n b\n}\n"} lines 1-3 are one fold.
 */
public class TestFoldingEditor extends TextEditor {

    @Override
    protected ISourceViewer createSourceViewer(Composite parent, IVerticalRuler ruler, int styles) {
        ProjectionViewer viewer = new ProjectionViewer(parent, ruler, getOverviewRuler(), isOverviewRulerVisible(),
                styles);
        getSourceViewerDecorationSupport(viewer); // as the base class does for its own viewer
        return viewer;
    }

    @Override
    public void createPartControl(Composite parent) {
        super.createPartControl(parent);
        ProjectionViewer viewer = (ProjectionViewer) getSourceViewer();
        new ProjectionSupport(viewer, getAnnotationAccess(), getSharedColors()).install();
        viewer.enableProjection();
        updateFolds();
    }

    /** Replaces the folds with the brace blocks of the document as it is now, all expanded. */
    public void updateFolds() {
        ProjectionViewer viewer = (ProjectionViewer) getSourceViewer();
        ProjectionAnnotationModel model = viewer.getProjectionAnnotationModel();
        IDocument document = viewer.getDocument();
        if (model == null || document == null) {
            return;
        }
        Map<Annotation, Position> folds = new HashMap<>();
        Deque<Integer> open = new ArrayDeque<>();
        try {
            for (int line = 0; line < document.getNumberOfLines(); line++) {
                String text = document.get(document.getLineOffset(line), document.getLineLength(line)).strip();
                if (text.endsWith("{")) {
                    open.push(line);
                } else if (text.startsWith("}") && !open.isEmpty()) {
                    int first = document.getLineOffset(open.pop());
                    int end = line + 1 < document.getNumberOfLines() ? document.getLineOffset(line + 1) : document.getLength();
                    folds.put(new ProjectionAnnotation(), new Position(first, end - first));
                }
            }
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
        model.modifyAnnotations(model.getAnnotationIterator().hasNext() ? allOf(model) : null, folds, null);
    }

    private static Annotation[] allOf(ProjectionAnnotationModel model) {
        java.util.List<Annotation> all = new java.util.ArrayList<>();
        model.getAnnotationIterator().forEachRemaining(all::add);
        return all.toArray(Annotation[]::new);
    }
}
