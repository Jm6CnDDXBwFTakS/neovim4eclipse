// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.LineTextUtil;

/**
 * Remembers, per document, whether its last line break is a final newline after Neovim's last
 * line (Vim's {@code 'eol'}), for everything that moves text between that document and its Neovim
 * buffer: {@link BufferSynchronizer} while an editor shows it, {@link UnattachedDocumentSync}
 * otherwise. One place, because a document moves between the two (its editor is switched away
 * from, and {@code :bufdo} then edits its buffer), and both must agree with the buffer.
 *
 * <p>Why it is remembered and not read from the text: see {@link LineTextUtil}. Example: after
 * {@code o} on the last line of {@code "a"}, the text {@code "a\n"} is {@code ["a", ""]} without a
 * final newline; read from the text it would be {@code ["a"]} with one.
 *
 * <p>Weak keys: an entry goes when its document is no longer used. UI thread, like the documents.
 *
 * <p>Public for {@link #neovimLineCount}, which the editor needs to keep Neovim's cursor inside the
 * buffer; the rest stays with {@code sync/}.
 */
public final class FinalNewlines {

    private static final Map<IDocument, Boolean> KNOWN = Collections.synchronizedMap(new WeakHashMap<>());

    private FinalNewlines() {
    }

    /**
     * Whether {@code document} has a final newline: as decided at its first push, or, before any
     * push, read from its text (all there is to go by then).
     */
    static boolean of(IDocument document) {
        Boolean known = KNOWN.get(document);
        return known != null ? known : LineTextUtil.endsWithLineBreak(document.get());
    }

    /**
     * How many lines Neovim's buffer has for {@code document} as it stands: Eclipse's lines, less
     * the empty one Eclipse shows after a final newline, which Neovim does not have (Vim's
     * {@code 'eol'}). The same count {@link LineTextUtil#toNeovim} gives, from the last character
     * alone, so it is cheap enough for every caret move in a long file.
     *
     * <p>Example: {@code "alpha\nbeta\n"} has 3 lines in Eclipse (the third empty) and 2 in Neovim.
     * After {@code o} on the last line of {@code "a"}, {@code "a\n"} has no final newline: its
     * empty second line is a line in Neovim too, so the count is 2.
     */
    public static int neovimLineCount(IDocument document) {
        int lines = document.getNumberOfLines();
        int length = document.getLength();
        if (length == 0) {
            return 1;
        }
        try {
            char last = document.getChar(length - 1);
            boolean endsWithBreak = last == '\n' || last == '\r';
            Boolean known = KNOWN.get(document);
            boolean finalNewline = endsWithBreak && (known == null || known);
            return finalNewline ? Math.max(1, lines - 1) : lines;
        } catch (BadLocationException e) {
            return lines; // the length was just read; cannot happen on the UI thread
        }
    }

    /**
     * The document's lines for Neovim ({@link LineTextUtil#toNeovim}), keeping its final newline as
     * decided before, without deciding it if it was not: a read, for the UI tests' text check at the
     * end of each test ({@code SyncProbe} in the tests). Read on the UI thread.
     */
    static List<String> linesForNeovim(IDocument document) {
        return LineTextUtil.toNeovim(document.get(), KNOWN.get(document)).lines();
    }

    /**
     * Like {@link #linesForNeovim}, for a push: also remembers the final newline decided, so that
     * later reads and pushes keep it. Read on the UI thread.
     */
    static List<String> linesToPush(IDocument document) {
        LineTextUtil.NeovimLines push = LineTextUtil.toNeovim(document.get(), KNOWN.get(document));
        KNOWN.put(document, push.finalNewline());
        return push.lines();
    }
}
