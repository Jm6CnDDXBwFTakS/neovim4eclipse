// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

/**
 * The Neovim buffer that mirrors one Eclipse document, so that each file keeps its own undo
 * history and marks. Created on first {@link NeovimSession#showBuffer}; belongs to that session only (a
 * restarted Neovim creates a new one). Discard it with {@link NeovimSession#closeBufferAsync}.
 */
public final class DocumentBuffer {
    final String name;
    /** The registry that created the buffer; {@code null} before first use and after wiping. */
    volatile DocumentBuffers owner;
    volatile int bufnr = NotificationBridge.UNKNOWN_BUFFER;
    /**
     * Whether the document's text is in the buffer yet. The first load is kept out of the undo
     * history, so that {@code u} in a freshly opened file does not empty it.
     */
    volatile boolean loaded;

    /** @param name buffer name shown by {@code :ls} and {@code %f}, normally the file path; may be empty */
    public DocumentBuffer(String name) {
        this.name = name == null ? "" : name;
    }

    /** The buffer name, normally the file path; may be empty. */
    public String name() {
        return name;
    }

    /** Neovim's buffer number, or {@link NotificationBridge#UNKNOWN_BUFFER} before first use. */
    public int bufnr() {
        return bufnr;
    }

    /** Whether the buffer exists in {@code session} (not yet created, or from a restarted Neovim, if not). */
    public boolean isOpenIn(NeovimSession session) {
        return session.buffers().owns(this);
    }
}
