// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.sync;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.LineRangeEdit.LineSource;

/**
 * An {@link IDocument} seen as a {@link LineSource}, the small line view that the pure classes in
 * {@code util} work on ({@code LineRangeEdit}, {@code VisualSelectionGeometry}). Unit tests give
 * those classes a string-backed source instead. UI thread only, like the document.
 *
 * <p>A {@link BadLocationException} here means a caller passed a line or offset outside the
 * document; it is rethrown unchecked, since the pure callers have no use for JFace's checked type.
 */
public final class DocumentLineSource implements LineSource {

    private final IDocument doc;

    public DocumentLineSource(IDocument doc) {
        this.doc = doc;
    }

    @Override
    public int lineCount() {
        return doc.getNumberOfLines();
    }

    @Override
    public int lineOffset(int line) {
        try {
            return doc.getLineOffset(line);
        } catch (BadLocationException e) {
            throw new IllegalStateException("no line " + line, e);
        }
    }

    @Override
    public int lineContentLength(int line) {
        try {
            return doc.getLineInformation(line).getLength();
        } catch (BadLocationException e) {
            throw new IllegalStateException("no line " + line, e);
        }
    }

    @Override
    public int length() {
        return doc.getLength();
    }

    @Override
    public String text(int offset, int length) {
        try {
            return doc.get(offset, length);
        } catch (BadLocationException e) {
            throw new IllegalStateException("no text at " + offset + "+" + length, e);
        }
    }
}
