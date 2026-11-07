// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextPresentationListener;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension2;
import org.eclipse.jface.text.ITextViewerExtension4;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.TextPresentation;
import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.SearchMatch;

/**
 * Paints the matches of Neovim's search behind the editor's text (docs/design/SEARCH_HIGHLIGHTING.md),
 * and the match under the caret a shade stronger, as Neovim's {@code CurSearch} does.
 *
 * <h2>Behind the text, with JFace's own mechanism</h2>
 * A JFace text viewer builds what the widget shows (the syntax colors) as a
 * {@link TextPresentation}, and lets {@link ITextPresentationListener}s add to it before it is
 * applied. This class is one: it merges a background color into the presentation's ranges that a
 * match covers ({@link TextPresentation#mergeStyleRange}, which keeps the foreground and font), so
 * the syntax colors stay, and the selection is still drawn over the match. Eclipse's own
 * highlighted annotations (Mark Occurrences shown as "Highlighted") work the same way. When the
 * matches change, {@link ITextViewerExtension2#invalidateTextPresentation} has the viewer build
 * the presentation of the changed region again, and this listener adds the new backgrounds.
 *
 * <p>The presentation is in document offsets, and the viewer maps it to the widget itself, so
 * folded code needs nothing here: a match inside a closed fold is simply not shown.
 *
 * <p>Matches arrive in Neovim's units (lines from 1, UTF-8 byte columns) and become document
 * offsets with {@link DocumentPositions#byteColToOffset}. Example: {@code foo} after {@code é } on
 * line 1 is bytes 3 to 6, characters 2 to 5. Only the lines Neovim's window shows are sent, which
 * are the lines the editor shows. UI thread only.
 */
final class SearchHighlighter implements ITextPresentationListener {

    private static final Logger LOG = Logger.getLogger(SearchHighlighter.class.getName());

    private final ITextViewer viewer;
    private final StyledText widget;
    private final CaretListener caretListener = event -> onCaretMoved();
    private Color matchColor;
    private Color currentColor;
    /** The matches as document regions, in Neovim's order; empty when nothing is highlighted. */
    private List<IRegion> regions = List.of();
    /** The index in {@link #regions} of the match under the caret, or -1. */
    private int current = -1;

    /**
     * @param viewer       the editor's viewer; it must support presentation listeners and
     *                     invalidation ({@link #supports}), as every JFace text viewer does
     * @param matchColor   the background of a match
     * @param currentColor the background of the match under the caret
     */
    SearchHighlighter(ITextViewer viewer, Color matchColor, Color currentColor) {
        this.viewer = viewer;
        this.widget = viewer.getTextWidget();
        this.matchColor = matchColor;
        this.currentColor = currentColor;
        ((ITextViewerExtension4) viewer).addTextPresentationListener(this);
        widget.addCaretListener(caretListener);
    }

    /** Whether {@code viewer} can show the highlights: it takes presentation listeners and invalidation. */
    static boolean supports(ITextViewer viewer) {
        return viewer instanceof ITextViewerExtension4 && viewer instanceof ITextViewerExtension2
                && viewer.getTextWidget() != null;
    }

    /** Shows {@code matches} (Neovim's units) in place of the ones shown before; empty clears. */
    void show(List<SearchMatch> matches) {
        IDocument document = viewer.getDocument();
        List<IRegion> next = document == null ? List.of() : toRegions(document, matches);
        if (next.equals(regions)) {
            return; // the same matches: nothing to paint again
        }
        List<IRegion> old = regions;
        regions = next;
        current = indexAt(next, caretOffset());
        repaint(old, next);
    }

    /** Clears the highlights (Neovim mode off, or the editor detaches). */
    void clear() {
        show(List.of());
    }

    /** New colors (the user changed the Search Results color, or the theme). */
    void setColors(Color match, Color currentMatch) {
        matchColor = match;
        currentColor = currentMatch;
        repaint(regions, List.of());
    }

    /** The regions shown, in document offsets; for tests. */
    List<IRegion> regions() {
        return regions;
    }

    /** The index of the match painted as the current one, or -1; for tests. */
    int current() {
        return current;
    }

    /** Stops painting and listening. */
    void dispose() {
        List<IRegion> old = regions;
        regions = List.of();
        current = -1;
        ((ITextViewerExtension4) viewer).removeTextPresentationListener(this);
        if (!widget.isDisposed()) {
            widget.removeCaretListener(caretListener);
            repaint(old, List.of());
        }
    }

    @Override
    public void applyTextPresentation(TextPresentation presentation) {
        IRegion extent = presentation.getExtent();
        if (extent == null || regions.isEmpty()) {
            return;
        }
        int from = extent.getOffset();
        int to = from + extent.getLength();
        for (int i = 0; i < regions.size(); i++) {
            IRegion r = regions.get(i);
            int start = Math.max(r.getOffset(), from);
            int end = Math.min(r.getOffset() + r.getLength(), to);
            if (start < end) {
                // No foreground and no font: only the background is merged, the syntax colors stay.
                presentation.mergeStyleRange(new StyleRange(start, end - start, null, i == current ? currentColor : matchColor));
            }
        }
    }

    /** The caret moved ({@code n}, a click): the match under it is now the current one. */
    private void onCaretMoved() {
        int next = indexAt(regions, caretOffset());
        if (next != current) {
            IRegion before = current >= 0 ? regions.get(current) : null;
            current = next;
            List<IRegion> changed = new ArrayList<>(2);
            if (before != null) {
                changed.add(before);
            }
            if (next >= 0) {
                changed.add(regions.get(next));
            }
            repaint(changed, List.of());
        }
    }

    /** Has the viewer build the presentation again over every region of {@code a} and {@code b}. */
    private void repaint(List<IRegion> a, List<IRegion> b) {
        int start = Integer.MAX_VALUE;
        int end = -1;
        for (List<IRegion> list : List.of(a, b)) {
            for (IRegion r : list) {
                start = Math.min(start, r.getOffset());
                end = Math.max(end, r.getOffset() + r.getLength());
            }
        }
        if (end <= start || widget.isDisposed()) {
            return;
        }
        try {
            ((ITextViewerExtension2) viewer).invalidateTextPresentation(start, end - start);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "search highlight repaint failed", e); // e.g. the document changed under us
        }
    }

    /** The caret as a document offset (the widget's offset differs where code is folded). */
    private int caretOffset() {
        if (widget.isDisposed()) {
            return -1;
        }
        int caret = widget.getCaretOffset();
        return viewer instanceof ITextViewerExtension5 extension ? extension.widgetOffset2ModelOffset(caret) : caret;
    }

    /**
     * The index of the region that holds {@code offset} (from its start up to, not including, its
     * end), or -1. Neovim's cursor sits on a match's first character after {@code n}, so the match
     * it lands on is the current one.
     */
    static int indexAt(List<IRegion> regions, int offset) {
        for (int i = 0; i < regions.size(); i++) {
            IRegion r = regions.get(i);
            if (offset >= r.getOffset() && offset < r.getOffset() + r.getLength()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The matches as document regions; a match the document does not have (it changed since Neovim
     * looked) or that comes out empty is left out.
     */
    static List<IRegion> toRegions(IDocument document, List<SearchMatch> matches) {
        List<IRegion> out = new ArrayList<>(matches.size());
        int lines = document.getNumberOfLines();
        for (SearchMatch m : matches) {
            if (m.startLine1() < 1 || m.endLine1() > lines) {
                continue; // byteColToOffset would clamp it onto another line
            }
            try {
                int start = DocumentPositions.byteColToOffset(document, m.startLine1(), m.startByte());
                int end = DocumentPositions.byteColToOffset(document, m.endLine1(), m.endByte());
                if (end > start) {
                    out.add(new Region(start, end - start));
                }
            } catch (BadLocationException e) {
                // A line the document does not have: skipped.
            }
        }
        return List.copyOf(out);
    }
}
