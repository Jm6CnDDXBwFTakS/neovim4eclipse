// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.JumpLabels;

/**
 * Draws a jump plug-in's labels over the editor's text ({@link JumpLabels},
 * docs/design/SEARCH_HIGHLIGHTING.md, "Jump labels").
 *
 * <p><b>Why a paint listener.</b> A label is a letter shown <i>in place of</i> a character, and
 * the document must not change for it: the text is the user's, and Neovim's copy has no such
 * letter either. {@code StyledText} cannot show other text than its content, but it lets a
 * {@link PaintListener} draw over what it painted. So each label is a filled cell with its letter,
 * drawn where the character it covers was drawn. Example: flash's {@code a} over the {@code l} of
 * {@code alpha}: the cell of the {@code l} is filled with the label's background and {@code a}
 * is drawn in it; the document still says {@code alpha}.
 *
 * <p><b>Where.</b> A label names a line and a byte column in Neovim's units. They are turned into
 * a place on the screen at each paint, not kept as offsets: the document may change between two
 * reports of Neovim's, and a label whose line the document no longer has is then simply not
 * drawn. A column at or past the line's end is the line's end: flash puts the label of a match
 * that ends a line there. A label in folded text has no place in the widget
 * ({@link WidgetOffsets}) and is not drawn. The cell is as
 * wide as the editor font's average character, which is every character's width in the monospaced
 * fonts code is edited in; in a proportional font a label can be a little wider or narrower than
 * the character under it.
 *
 * <p>UI thread only.
 */
final class JumpLabelPainter {

    private static final Logger LOG = Logger.getLogger(JumpLabelPainter.class.getName());

    /** A label and the rectangle it was last painted in; for tests. */
    record Painted(JumpLabels.Label label, Rectangle cell) {
    }

    private final ITextViewer viewer;
    private final StyledText widget;
    private final WidgetOffsets offsets;
    private final PaintListener painter = event -> paint(event.gc);
    /** The labels to draw; replaced, never changed. */
    private List<JumpLabels.Label> labels = List.of();
    /** The editor's font in bold, made when a bold label is first drawn and disposed with this painter. */
    private Font bold;
    /** The font {@link #bold} was made from: the user may change the editor's font. */
    private Font boldOf;
    private boolean listening;

    JumpLabelPainter(ITextViewer viewer) {
        this.viewer = viewer;
        this.widget = viewer.getTextWidget();
        this.offsets = new WidgetOffsets(widget, viewer);
    }

    /** Draws {@code next} in place of the labels drawn before; empty removes them. */
    void show(List<JumpLabels.Label> next) {
        if (widget == null || widget.isDisposed() || next.equals(labels)) {
            return;
        }
        labels = List.copyOf(next);
        // Listen only while there is something to draw: every paint of the editor calls a listener.
        if (labels.isEmpty() == listening) {
            if (listening) {
                widget.removePaintListener(painter);
            } else {
                widget.addPaintListener(painter);
            }
            listening = !listening;
        }
        widget.redraw(); // the old labels go, the new ones show
    }

    /** The labels as they would be painted now, those without a place left out; for tests. */
    List<Painted> painted() {
        if (widget == null || widget.isDisposed() || labels.isEmpty()) {
            return List.of();
        }
        GC gc = new GC(widget);
        try {
            return cells(gc);
        } finally {
            gc.dispose();
        }
    }

    void dispose() {
        if (widget != null && !widget.isDisposed()) {
            if (listening) {
                widget.removePaintListener(painter);
                listening = false;
            }
            if (!labels.isEmpty()) {
                widget.redraw();
            }
        }
        labels = List.of();
        if (bold != null) {
            bold.dispose();
            bold = null;
            boldOf = null;
        }
    }

    private void paint(GC gc) {
        List<Painted> cells;
        try {
            cells = cells(gc);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "jump label paint failed", e); // e.g. the document changed under us
            return;
        }
        Color oldBackground = gc.getBackground();
        Color oldForeground = gc.getForeground();
        Font oldFont = gc.getFont();
        try {
            for (Painted p : cells) {
                JumpLabels.Label label = p.label();
                gc.setBackground(label.background() == JumpLabels.NO_COLOR ? widget.getBackground() : color(label.background()));
                gc.setForeground(label.foreground() == JumpLabels.NO_COLOR ? widget.getForeground() : color(label.foreground()));
                gc.setFont(label.bold() ? boldFont() : widget.getFont());
                gc.fillRectangle(p.cell());
                gc.drawText(label.text(), p.cell().x, p.cell().y, true);
            }
        } finally {
            gc.setBackground(oldBackground);
            gc.setForeground(oldForeground);
            gc.setFont(oldFont);
        }
    }

    /** Each label that has a place in the widget now, with the cell it covers. */
    private List<Painted> cells(GC gc) {
        IDocument document = viewer.getDocument();
        if (document == null) {
            return List.of();
        }
        gc.setFont(widget.getFont());
        int cellWidth = Math.max(1, (int) Math.round(gc.getFontMetrics().getAverageCharacterWidth()));
        int chars = widget.getCharCount();
        List<Painted> cells = new ArrayList<>(labels.size());
        for (JumpLabels.Label label : labels) {
            if (label.line1() > document.getNumberOfLines()) {
                continue; // a line the document does not have (any more)
            }
            int offset;
            try {
                offset = DocumentPositions.byteColToOffset(document, label.line1(), label.col0());
            } catch (BadLocationException e) {
                continue;
            }
            int widgetOffset = offsets.toWidget(offset);
            if (widgetOffset < 0 || widgetOffset > chars) {
                continue; // inside a closed fold
            }
            Point at = widget.getLocationAtOffset(widgetOffset);
            int height = widget.getLineHeight(Math.min(widgetOffset, chars));
            int width = cellWidth * Math.max(1, label.text().codePointCount(0, label.text().length()));
            cells.add(new Painted(label, new Rectangle(at.x + label.cells() * cellWidth, at.y, width, height)));
        }
        return cells;
    }

    private Font boldFont() {
        Font base = widget.getFont();
        if (bold == null || boldOf != base) {
            if (bold != null) {
                bold.dispose();
            }
            FontData[] data = base.getFontData();
            for (FontData d : data) {
                d.setStyle(d.getStyle() | SWT.BOLD);
            }
            bold = new Font(widget.getDisplay(), data);
            boldOf = base;
        }
        return bold;
    }

    /** {@code 0xRRGGBB} as an SWT color (one made from RGB values needs no disposing). */
    private static Color color(int rgb) {
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }
}
