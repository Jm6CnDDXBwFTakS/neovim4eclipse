// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelListener;
import org.eclipse.jface.text.source.projection.IProjectionListener;
import org.eclipse.jface.text.source.projection.IProjectionPosition;
import org.eclipse.jface.text.source.projection.ProjectionAnnotation;
import org.eclipse.jface.text.source.projection.ProjectionAnnotationModel;
import org.eclipse.jface.text.source.projection.ProjectionViewer;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.UiExecutor;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FoldRegions;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FoldRegions.Fold;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FoldRegions.Region;

/**
 * Keeps one editor's folds and Neovim's the same (docs/design/FOLDING.md): Eclipse's fold regions go
 * to Neovim, and the folds Neovim opens or closes are expanded or collapsed in Eclipse.
 *
 * <p><b>Eclipse's side of folding.</b> An editor that folds shows its text through a
 * {@link ProjectionViewer}. Each fold is a {@link ProjectionAnnotation} in the viewer's
 * {@link ProjectionAnnotationModel}, with a {@link Position} over the lines it covers; collapsing the
 * annotation hides those lines in the widget except its <em>caption line</em>. For a Java method with
 * Javadoc the caption is the signature, not the first line: the position is an
 * {@link IProjectionPosition}, whose {@code computeCaptionOffset} says where the caption starts.
 *
 * <p><b>Both directions.</b>
 * <ul>
 *   <li><b>To Neovim</b> ({@link #send()}): every annotation's lines and state, through
 *       {@link FoldRegions#forNeovim}, at attach and after every change of the model that did not
 *       come from Neovim: a reconcile (new or moved methods), a fold collapsed with the ruler or
 *       {@code Ctrl}+{@code Numpad-}, Open Declaration unfolding its target. A reconcile changes many
 *       annotations at once, so changes are coalesced: one send per UI-thread turn.</li>
 *   <li><b>From Neovim</b> ({@link #applyNeovimState}): the indexes of the outermost closed folds,
 *       read after each key. {@link FoldRegions#stateChange} turns them into annotations to
 *       collapse and expand. Those changes are made with a flag set, so the model listener does not
 *       send them back.</li>
 * </ul>
 *
 * <p>Example: {@code zc} on a method. Neovim closes its fold; the state after the key lists the
 * method's index; this class collapses its annotation; the widget hides the body; the caret goes to
 * the method's caption line ({@link #captionOffset}).
 *
 * <p>When projection is off (the user turned folding off in the editor), Neovim gets an empty list,
 * which turns folding off in Neovim's window too; turning it on sends the regions again.
 *
 * <p>SWT and JFace only, no workbench, so {@code FoldMirrorTest} (tests/swt) runs it on a real
 * {@link ProjectionViewer}. UI thread, except the model listener, which may be called from the
 * reconciler's thread and only schedules a send.
 */
final class FoldMirror implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(FoldMirror.class.getName());

    private final ProjectionViewer viewer;
    private final UiExecutor ui;
    /** Sends the folds to Neovim ({@code FoldsPort.setFoldsAsync} for this editor's buffer). */
    private final Consumer<List<Fold>> sender;

    /** The list last sent; Neovim reports folds by their index in it. */
    private List<Fold> sent = List.of();
    /**
     * The annotations behind each sent fold, by index. Usually one; two annotations over the same
     * lines become one fold ({@link FoldRegions#forNeovim}) and are collapsed and expanded together.
     */
    private List<List<ProjectionAnnotation>> annotationsByIndex = List.of();
    /** True while Neovim's state is being applied: those model changes are not sent back. */
    private boolean applyingNeovimState;
    /** A send is scheduled for this UI-thread turn. Any thread. */
    private volatile boolean sendScheduled;
    private volatile boolean closed;
    /** The model listened to; it changes when projection is turned off and on again. */
    private ProjectionAnnotationModel model;

    private final IAnnotationModelListener modelListener = this::onModelChanged;
    private final IProjectionListener projectionListener = new IProjectionListener() {
        @Override
        public void projectionEnabled() {
            listenToModel();
            scheduleSend();
        }

        @Override
        public void projectionDisabled() {
            listenToModel();
            scheduleSend();
        }
    };

    FoldMirror(ProjectionViewer viewer, UiExecutor ui, Consumer<List<Fold>> sender) {
        this.viewer = viewer;
        this.ui = ui;
        this.sender = sender;
        viewer.addProjectionListener(projectionListener);
        listenToModel();
    }

    /** Listens to the viewer's current annotation model ({@code null} while projection is off). */
    private void listenToModel() {
        ProjectionAnnotationModel current = viewer.getProjectionAnnotationModel();
        if (current == model) {
            return;
        }
        if (model != null) {
            model.removeAnnotationModelListener(modelListener);
        }
        model = current;
        if (model != null) {
            model.addAnnotationModelListener(modelListener);
        }
    }

    /** Whether the viewer folds now: projection on and a model there. */
    private boolean folding() {
        return viewer.isProjectionMode() && viewer.getProjectionAnnotationModel() != null;
    }

    // ---- to Neovim ----

    /**
     * Sends every fold region and its state to Neovim now (an empty list when the viewer does not
     * fold). Called at attach, after the document push was queued, so that the lines exist in Neovim
     * when the folds arrive (both run in order on the session's input thread). UI thread.
     */
    void send() {
        if (closed) {
            return;
        }
        sendScheduled = false;
        IDocument document = viewer.getDocument();
        if (!folding() || document == null) {
            sent = List.of();
            annotationsByIndex = List.of();
            sender.accept(List.of());
            return;
        }
        // The lines of each annotation, and the annotations behind each range (see annotationsByIndex).
        List<Region> regions = new ArrayList<>();
        Map<String, List<ProjectionAnnotation>> byRange = new LinkedHashMap<>();
        for (Iterator<Annotation> it = model.getAnnotationIterator(); it.hasNext();) {
            if (!(it.next() instanceof ProjectionAnnotation annotation) || annotation.isMarkedDeleted()) {
                continue;
            }
            Position position = model.getPosition(annotation);
            Region region = regionOf(document, position, annotation.isCollapsed());
            if (region != null) {
                regions.add(region);
                byRange.computeIfAbsent(key(region.firstLine(), region.lastLine()), k -> new ArrayList<>())
                        .add(annotation);
            }
        }
        List<Fold> folds = FoldRegions.forNeovim(regions);
        List<List<ProjectionAnnotation>> annotations = new ArrayList<>(folds.size());
        for (Fold fold : folds) {
            annotations.add(byRange.getOrDefault(key(fold.firstLine(), fold.lastLine()), List.of()));
        }
        sent = folds;
        annotationsByIndex = annotations;
        sender.accept(folds);
    }

    /**
     * The 1-based lines a fold's position covers, or {@code null} if it is gone or outside the
     * document. Eclipse's positions run from the start of the first line to the start of the line
     * after the last one (they include its line break), so the last line is the one holding the last
     * character: {@code "b\nc\nd\n"} at offset 2 covers lines 2 to 4.
     */
    static Region regionOf(IDocument document, Position position, boolean collapsed) {
        if (position == null || position.isDeleted() || position.getLength() <= 0) {
            return null;
        }
        try {
            int first = document.getLineOfOffset(position.getOffset());
            int last = document.getLineOfOffset(position.getOffset() + position.getLength() - 1);
            return new Region(first + 1, last + 1, collapsed);
        } catch (BadLocationException e) {
            return null;
        }
    }

    private static String key(int first, int last) {
        return first + ":" + last;
    }

    /** The model changed: unless the change is Neovim's own, send the folds again (coalesced). */
    private void onModelChanged(IAnnotationModel changed) {
        if (applyingNeovimState || closed) {
            return;
        }
        scheduleSend();
    }

    private void scheduleSend() {
        if (sendScheduled || closed) {
            return;
        }
        sendScheduled = true;
        ui.asyncExec(this::send);
    }

    // ---- from Neovim ----

    /**
     * Collapses and expands Eclipse's folds to match Neovim's (the outermost closed folds' indexes,
     * read after a key). Folds inside a closed one keep Eclipse's state ({@link FoldRegions#stateChange}).
     * Must run before the caret is moved, so that the caret lands on text that is shown. UI thread.
     */
    void applyNeovimState(List<Integer> closedInNeovim) {
        if (closed || !folding() || sent.isEmpty()) {
            return;
        }
        List<Boolean> collapsed = new ArrayList<>(annotationsByIndex.size());
        for (List<ProjectionAnnotation> annotations : annotationsByIndex) {
            collapsed.add(annotations.stream().anyMatch(ProjectionAnnotation::isCollapsed));
        }
        FoldRegions.StateChange change = FoldRegions.stateChange(sent, collapsed, new HashSet<>(closedInNeovim));
        if (change.isEmpty()) {
            return;
        }
        applyingNeovimState = true;
        try {
            // Expand first: an outer fold opened by zo then shows its inner folds, collapsed after.
            for (int index : change.expand()) {
                annotationsByIndex.get(index).forEach(model::expand);
            }
            for (int index : change.collapse()) {
                annotationsByIndex.get(index).forEach(model::collapse);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "applying Neovim's folds failed", e);
        } finally {
            applyingNeovimState = false;
        }
    }

    // ---- the caret ----

    /**
     * Where the caret goes for a document offset hidden in a collapsed fold that Neovim has closed
     * too: the start of that fold's caption line, so that Eclipse does not unfold what Neovim keeps
     * closed. Returns -1 when the offset is not inside a mirrored collapsed fold (then the caller
     * reveals it, as before folds were mirrored). Of nested collapsed folds, the outermost wins: it
     * is the one the widget shows.
     *
     * <p>Example: a collapsed method with Javadoc on lines 10-20 whose signature is line 14; Neovim's
     * cursor on line 12 (inside the closed fold) puts the caret at the start of line 14.
     */
    int captionOffset(int modelOffset) {
        if (closed || !folding() || annotationsByIndex.isEmpty()) {
            return -1;
        }
        IDocument document = viewer.getDocument();
        int best = -1;
        int bestLength = -1;
        for (List<ProjectionAnnotation> annotations : annotationsByIndex) {
            for (ProjectionAnnotation annotation : annotations) {
                Position position = model.getPosition(annotation);
                if (!annotation.isCollapsed() || position == null || position.isDeleted()
                        || modelOffset < position.getOffset() || modelOffset >= position.getOffset() + position.getLength()) {
                    continue;
                }
                if (position.getLength() > bestLength) {
                    bestLength = position.getLength();
                    best = captionStart(document, position);
                }
            }
        }
        return best;
    }

    /** The start of a collapsed position's caption line. */
    private static int captionStart(IDocument document, Position position) {
        if (position instanceof IProjectionPosition projection && document != null) {
            try {
                int caption = position.getOffset() + projection.computeCaptionOffset(document);
                return document.getLineOffset(document.getLineOfOffset(caption));
            } catch (BadLocationException e) {
                // fall back to the first line
            }
        }
        return position.getOffset();
    }

    /** For tests: the list last sent. */
    List<Fold> sentFolds() {
        return sent;
    }

    @Override
    public void close() {
        closed = true;
        viewer.removeProjectionListener(projectionListener);
        if (model != null) {
            model.removeAnnotationModelListener(modelListener);
            model = null;
        }
    }
}
