// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The Eclipse side of the per-document Neovim buffers: which editors show each document, and so
 * which {@link DocumentBuffer} each editor uses. {@link DocumentBuffers} is the Neovim side (which
 * buffers exist in a session, and which one is attached); this class only keeps the bookkeeping that
 * decides when a buffer is shared, found and given up.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li><b>Shared:</b> two editors on one document (<em>Window → New Editor</em>, or a multi-page
 *       editor and a plain one on the same file) get the same buffer, so they share undo history
 *       and marks, as two Vim windows on one buffer do.</li>
 *   <li><b>Found:</b> when Neovim switches to buffer N by itself ({@code Ctrl-O} across files,
 *       {@code 'A}, {@code :b N}), {@link #editorShowing} names the editor Eclipse should activate:
 *       the <em>first one opened</em> on that document that is still open, so the choice does not
 *       depend on hash order.</li>
 *   <li><b>Given up:</b> a buffer is reference-counted by its editors. {@link #remove} returns it
 *       once its last editor closed, and the caller wipes it in Neovim
 *       ({@link NeovimSession#closeBufferAsync}). Example: {@code A.java} open in editors 1 and 2;
 *       closing 1 returns nothing, closing 2 returns {@code A.java}'s buffer.</li>
 * </ul>
 *
 * <p>Why generic: the document and editor types are Eclipse's ({@code IDocument},
 * {@code IEditorPart}), which {@code core/} must not import. As type parameters they keep this class
 * free of Eclipse, so {@code tests/unit} tests it with plain strings, while
 * {@code editor/EditorAttachManager} keeps only the workbench glue (listeners, multi-page tabs).
 *
 * <p>Not thread-safe: the caller guards every call (the attach manager uses its own lock).
 *
 * @param <D> the document type; compared with {@code equals} and {@code hashCode}
 * @param <E> the editor type; compared with {@code equals} and {@code hashCode}
 */
public final class EditorBuffers<D, E> {

    /** An editor that shows the document Neovim switched to. */
    public record Showing<D, E>(D document, E editor) {
    }

    /** A document's buffer and its editors, in the order they were opened. */
    private static final class Entry<E> {
        final DocumentBuffer buffer;
        final Set<E> editors = new LinkedHashSet<>();

        Entry(DocumentBuffer buffer) {
            this.buffer = buffer;
        }
    }

    /** Insertion-ordered as well, so that every lookup is repeatable. */
    private final Map<D, Entry<E>> documents = new LinkedHashMap<>();

    /**
     * Records that {@code editor} shows {@code document} and returns the document's buffer: the
     * existing one when another editor already shows it, else a new one named {@code name.get()}.
     * Adding the same pair again changes nothing (an editor keeps its place in the order).
     *
     * @param name the buffer name, normally the file path; asked for only when a buffer is created
     */
    public DocumentBuffer add(D document, E editor, Supplier<String> name) {
        Entry<E> entry = documents.computeIfAbsent(document, d -> new Entry<>(new DocumentBuffer(name.get())));
        entry.editors.add(editor);
        return entry.buffer;
    }

    /**
     * The editor to activate when Neovim switched to buffer {@code bufnr} by itself, or empty when no
     * open editor shows that buffer (then Neovim is sent back to the attached buffer).
     *
     * @param openInSession whether a buffer exists in the current Neovim session. A buffer from a
     *        Neovim that was restarted has a stale number that may now belong to another buffer, so
     *        it must not match. The editor passes {@code b -> b.isOpenIn(session)}.
     */
    public Optional<Showing<D, E>> editorShowing(int bufnr, Predicate<DocumentBuffer> openInSession) {
        for (Map.Entry<D, Entry<E>> e : documents.entrySet()) {
            Entry<E> entry = e.getValue();
            if (entry.buffer.bufnr() == bufnr && openInSession.test(entry.buffer)) {
                return Optional.of(new Showing<>(e.getKey(), entry.editors.iterator().next()));
            }
        }
        return Optional.empty();
    }

    /**
     * Forgets {@code editor} (it closed) and returns the buffers no editor shows any more, for the
     * caller to wipe in Neovim. An editor can be listed under more than one document when Eclipse
     * reused it for another file, so the result may hold several buffers; usually it holds one or none.
     */
    public List<DocumentBuffer> remove(E editor) {
        List<DocumentBuffer> unused = new ArrayList<>();
        documents.values().removeIf(entry -> {
            if (entry.editors.remove(editor) && entry.editors.isEmpty()) {
                unused.add(entry.buffer);
                return true;
            }
            return false;
        });
        return unused;
    }
}
