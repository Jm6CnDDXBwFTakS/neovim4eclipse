// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import static io.github.jm6cnddxbwftaks.eclipseneovim.core.Rpc.await;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ensarsarajcic.neovim.java.api.NeovimApi;
import com.ensarsarajcic.neovim.java.api.buffer.NeovimBufferApi;
import com.ensarsarajcic.neovim.java.api.types.api.DeleteBufferOptions;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.NeovimSession.DocumentBuffer;

/**
 * The Neovim buffers that mirror Eclipse documents, and which of them is <em>attached</em>.
 *
 * <h2>Buffers in Neovim</h2>
 * A Neovim buffer is the text of one file in memory, with its own undo history and marks. A
 * <em>window</em> shows one buffer at a time; the embedded Neovim has a single window. Giving each
 * Eclipse document its own buffer (instead of reloading one buffer on every editor switch) keeps
 * undo history and marks per file, and lets Vim's own file jumps ({@code Ctrl-O} across files,
 * {@code 'A}, {@code :b}) work.
 *
 * <ul>
 *   <li>The <em>home</em> buffer is an unlisted scratch buffer, shown when no document is (at
 *       start-up, after the attached document's buffer was wiped).</li>
 *   <li>The <em>attached</em> buffer is the one mirrored into the attached editor: buffer reads and
 *       writes go to it, and only its line events and cursor positions reach Eclipse.</li>
 *   <li>When Neovim enters another buffer by itself, the enter handler decides: follow it (another
 *       open document) or switch back (help, {@code :e} of an unrelated file, a closed document).</li>
 * </ul>
 *
 * <p>Methods that talk to Neovim block and must run on the session's input thread.
 */
final class DocumentBuffers {

    private static final Logger LOG = Logger.getLogger(DocumentBuffers.class.getName());

    private final NeovimApi api;
    private final NeovimBufferApi home;
    /** Changed on the input thread only; read from any thread. */
    private volatile NeovimBufferApi attached;
    private volatile int attachedBufnr;
    /** Buffers created by {@link #show}, by number. */
    private final Map<Integer, NeovimBufferApi> created = new ConcurrentHashMap<>();
    /** Called with the number of a document buffer Neovim switched to by itself. */
    private volatile Consumer<Integer> enterHandler;

    DocumentBuffers(NeovimApi api, NeovimBufferApi home) {
        this.api = api;
        this.home = home;
        this.attached = home;
        this.attachedBufnr = bufnr(home);
    }

    static int bufnr(NeovimBufferApi buffer) {
        return (int) buffer.get().getId();
    }

    /** The buffer mirrored into the attached document (the home buffer when none is). */
    NeovimBufferApi attached() {
        return attached;
    }

    int attachedBufnr() {
        return attachedBufnr;
    }

    /**
     * Whether an event for {@code bufnr} concerns the attached document. Events whose buffer is
     * unknown are accepted, as they always were before buffers were per document.
     */
    boolean isAttached(int bufnr) {
        return bufnr == NotificationBridge.UNKNOWN_BUFFER || bufnr == attachedBufnr;
    }

    /** Whether {@code buffer} was created by this registry and still exists. */
    boolean owns(DocumentBuffer buffer) {
        return buffer.owner == this && buffer.bufnr > 0;
    }

    /** Makes {@code buffer} the attached buffer and Neovim's current one, creating it on first use. */
    void show(DocumentBuffer buffer) throws Exception {
        NeovimBufferApi target = owns(buffer) ? created.get(buffer.bufnr) : null;
        if (target == null) {
            target = create(buffer.name);
            buffer.bufnr = bufnr(target);
            buffer.owner = this;
        }
        // Attach first: the BufEnter this switch causes is then recognized as Eclipse's own.
        attached = target;
        attachedBufnr = buffer.bufnr;
        await(api.setCurrentBuffer(target.get()));
    }

    private NeovimBufferApi create(String name) throws Exception {
        // Listed, so :ls and :b see it. Scratch: buftype=nofile and no swap file, so Neovim never
        // writes the file itself (Eclipse saves it), and no filetype detection runs.
        NeovimBufferApi buffer = await(api.createBuffer(true, true));
        if (!name.isEmpty()) {
            try {
                await(buffer.setName(name));
            } catch (Exception e) {
                LOG.log(Level.FINE, "Buffer name already in use, left unnamed: " + name, e);
            }
        }
        // nvim_buf_attach: Neovim now sends nvim_buf_lines_event for this buffer. No initial
        // full-buffer event (false): the document is pushed into the buffer instead.
        await(buffer.attach(false, Map.of()));
        created.put(bufnr(buffer), buffer);
        return buffer;
    }

    /** Wipes a document's buffer, with its undo history and marks (its last editor closed). */
    void close(DocumentBuffer buffer) throws Exception {
        if (!owns(buffer)) {
            return;
        }
        NeovimBufferApi doomed = created.remove(buffer.bufnr);
        buffer.owner = null;
        if (doomed == null) {
            return;
        }
        if (attachedBufnr == buffer.bufnr) {
            attached = home;
            attachedBufnr = bufnr(home);
        }
        // Leave the buffer first: wiping the window's buffer makes Neovim enter some other one,
        // which Eclipse would follow by activating that editor.
        await(api.setCurrentBuffer(attached.get()));
        await(doomed.delete(new DeleteBufferOptions(true, false)));
    }

    /** Switches Neovim back to the attached buffer. */
    void returnToAttached() throws Exception {
        await(api.setCurrentBuffer(attached.get()));
    }

    void setEnterHandler(Consumer<Integer> handler) {
        this.enterHandler = handler;
    }

    /**
     * Neovim entered buffer {@code bufnr} ({@code BufEnter}). Another document's buffer goes to the
     * enter handler; anything else is undone with {@code returnBack}.
     */
    void onEntered(int bufnr, Runnable returnBack) {
        if (bufnr == attachedBufnr) {
            return; // Eclipse's own switch, or Neovim came back
        }
        Consumer<Integer> handler = enterHandler;
        if (handler != null && created.containsKey(bufnr)) {
            handler.accept(bufnr);
        } else {
            returnBack.run(); // help, :e of another file, a closed document, ...
        }
    }
}
