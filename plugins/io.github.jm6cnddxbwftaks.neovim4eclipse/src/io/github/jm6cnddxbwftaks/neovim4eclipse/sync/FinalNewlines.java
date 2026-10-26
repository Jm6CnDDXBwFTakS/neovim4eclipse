// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

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
 */
final class FinalNewlines {

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
     * The document's lines for Neovim, keeping its final newline as decided before
     * ({@link LineTextUtil#toNeovim}), and remembers the result. Read on the UI thread.
     */
    static List<String> linesToPush(IDocument document) {
        LineTextUtil.NeovimLines push = LineTextUtil.toNeovim(document.get(), KNOWN.get(document));
        KNOWN.put(document, push.finalNewline());
        return push.lines();
    }
}
