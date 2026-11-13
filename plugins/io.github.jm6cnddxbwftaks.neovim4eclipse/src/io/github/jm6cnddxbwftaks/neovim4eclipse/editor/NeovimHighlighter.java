// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextPresentationListener;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension2;
import org.eclipse.jface.text.ITextViewerExtension4;
import org.eclipse.jface.text.TextPresentation;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimHighlight;

/**
 * Paints Neovim's own highlights in the editor (docs/design/SEARCH_HIGHLIGHTING.md, "The yank flash
 * and window matches"): the flash after a yank and the window's matches ({@code :match},
 * {@code matchadd()}), each in its Neovim group's colors.
 *
 * <p>The same mechanism as {@link SearchHighlighter}: a presentation listener that merges a
 * background, and the group's text color when it sets one, into the ranges a highlight covers.
 * Example: {@code :match Error /TODO/} gives every {@code TODO} on screen {@code Error}'s
 * background (and its text color, which {@code Error} sets); {@code :match Mine /x/} with
 * {@code Mine} a background alone keeps the editor's syntax colors on each {@code x}.
 *
 * <p><b>Order.</b> The highlights come lowest priority first, and are merged in that order, so the
 * last lies on top: the flash (200) over a match (10). The viewer calls its presentation listeners in
 * the order they were added, and the editor adds this one after the search highlighter's, so a flash
 * or a match also lies over a search match, as in Neovim, where {@code 'hlsearch'} has the lowest
 * priority.
 *
 * <p><b>Following the text.</b> As the search matches do: a prenotified document listener moves the
 * highlights after an edit made on Eclipse's side by its length and drops one the edit touches
 * ({@link SearchRegions#follow}), until Neovim reports them again. UI thread only, except the
 * document listener, which runs on the thread that changed the document ({@link #lock}).
 */
final class NeovimHighlighter implements ITextPresentationListener {

    private static final Logger LOG = Logger.getLogger(NeovimHighlighter.class.getName());

    /** One highlight as painted: where in the document, and its colors ({@code foreground} may be {@code null}). */
    record Shown(IRegion region, Color background, Color foreground) {
    }

    private final ITextViewer viewer;
    private final StyledText widget;
    private final IDocument document;
    private final IDocumentListener follower = new IDocumentListener() {
        @Override
        public void documentAboutToBeChanged(DocumentEvent event) {
            // Nothing: the highlights move once the change is made, in documentChanged.
        }

        @Override
        public void documentChanged(DocumentEvent event) {
            onDocumentChanged(event.getOffset(), event.getLength(), event.getText() == null ? 0 : event.getText().length());
        }
    };
    /**
     * Guards every change of {@link #shown}: a document change may come on another thread than the
     * UI's (a Job), and {@link #show} replaces the list on the UI thread; without the lock one of
     * the two writes could be lost. Readers copy the list once: it is never changed, only replaced.
     */
    private final Object lock = new Object();
    /** The highlights painted, lowest priority first; empty when none. */
    private volatile List<Shown> shown = List.of();

    /** @param viewer the editor's viewer; it must support what {@link SearchHighlighter#supports} asks */
    NeovimHighlighter(ITextViewer viewer) {
        this.viewer = viewer;
        this.widget = viewer.getTextWidget();
        this.document = viewer.getDocument();
        ((ITextViewerExtension4) viewer).addTextPresentationListener(this);
        if (document != null) {
            document.addPrenotifiedDocumentListener(follower);
        }
    }

    /** Shows {@code highlights} (Neovim's units, lowest priority first) in place of the ones shown before; empty clears. */
    void show(List<NeovimHighlight> highlights) {
        List<Shown> next = new ArrayList<>(highlights.size());
        if (document != null) {
            for (NeovimHighlight h : highlights) {
                // One at a time: toRegions leaves out a range the document no longer has.
                for (IRegion r : SearchRegions.toRegions(document, List.of(h.range()))) {
                    next.add(new Shown(r, color(h.background()), h.foreground() == NeovimHighlight.NO_COLOR ? null : color(h.foreground())));
                }
            }
        }
        List<Shown> old;
        synchronized (lock) {
            old = shown;
            if (next.equals(old)) {
                return; // the same highlights: nothing to paint again
            }
            shown = List.copyOf(next);
        }
        repaint(old, next);
    }

    /** Clears the highlights (Neovim mode off, or the editor detaches). */
    void clear() {
        show(List.of());
    }

    /** The highlights painted; for tests. */
    List<Shown> shown() {
        return shown;
    }

    /** Stops painting and listening. */
    void dispose() {
        List<Shown> old;
        synchronized (lock) {
            old = shown;
            shown = List.of();
        }
        ((ITextViewerExtension4) viewer).removeTextPresentationListener(this);
        if (document != null) {
            document.removePrenotifiedDocumentListener(follower);
        }
        repaint(old, List.of());
    }

    @Override
    public void applyTextPresentation(TextPresentation presentation) {
        IRegion extent = presentation.getExtent();
        List<Shown> painted = shown; // read once: a change on another thread replaces the list (see lock)
        if (extent == null || painted.isEmpty()) {
            return;
        }
        int from = extent.getOffset();
        int to = from + extent.getLength();
        for (Shown h : painted) {
            int start = Math.max(h.region().getOffset(), from);
            int end = Math.min(h.region().getOffset() + h.region().getLength(), to);
            if (start < end) {
                // A null foreground keeps the syntax color; the font stays in any case.
                presentation.mergeStyleRange(new StyleRange(start, end - start, h.foreground(), h.background()));
            }
        }
    }

    /**
     * The document changed by {@code removed} characters at {@code offset} replaced with
     * {@code inserted}: the highlights after it move, one it touches is dropped
     * ({@link SearchRegions#follow}). The viewer repaints the change after this.
     */
    void onDocumentChanged(int offset, int removed, int inserted) {
        synchronized (lock) { // read, move, write: as one step against show() (see lock)
            List<Shown> before = shown;
            if (!before.isEmpty()) {
                shown = SearchRegions.follow(before, Shown::region, (h, r) -> new Shown(r, h.background(), h.foreground()),
                        offset, removed, inserted);
            }
        }
    }

    /** Has the viewer build the presentation again over every highlight of {@code a} and {@code b}. */
    private void repaint(List<Shown> a, List<Shown> b) {
        int start = Integer.MAX_VALUE;
        int end = -1;
        for (List<Shown> list : List.of(a, b)) {
            for (Shown h : list) {
                start = Math.min(start, h.region().getOffset());
                end = Math.max(end, h.region().getOffset() + h.region().getLength());
            }
        }
        if (end <= start || widget == null || widget.isDisposed()) {
            return;
        }
        try {
            ((ITextViewerExtension2) viewer).invalidateTextPresentation(start, end - start);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "highlight repaint failed", e); // e.g. the document changed under us
        }
    }

    /** {@code 0xRRGGBB} as an SWT color (one made from RGB values needs no disposing). */
    private static Color color(int rgb) {
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }
}
