// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

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
    /**
     * Whether the buffer is the file's buffer kept from a closed editor (Vim's {@code :bd} keeps it;
     * docs/design/BUFFER_NUMBERS.md): its first push then puts the cursor where it was left
     * ({@link NeovimSession#goToLastPositionIfReopened}). Cleared by that push.
     */
    volatile boolean reopened;
    /**
     * Whether Eclipse has unsaved changes in the document (its editor is dirty). Neovim's
     * {@code 'modified'} follows it ({@link NeovimSession#setModifiedAsync}), so that {@code :update}
     * and {@code :wall} write exactly what Eclipse would save, and {@code :ls} shows {@code +}.
     */
    volatile boolean eclipseDirty;

    /** No modification stamp: the buffer is not known to equal the document. */
    public static final long NO_STAMP = -1;
    /**
     * The document's modification stamp ({@code IDocumentExtension4.getModificationStamp()}) when the
     * buffer was last made equal to it, or {@link #NO_STAMP}. Set by the push when its editor is
     * detached ({@code sync/UnattachedDocumentSync}); cleared by any change Neovim makes to the
     * buffer, and when the buffer is made. An attach then skips its push when the document still has
     * that stamp ({@code sync/DocumentPush}): switching back to an editor whose document did not
     * change needs no reading of the whole document and buffer.
     */
    private volatile long syncedStamp = NO_STAMP;

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

    /** Records that the buffer equals the document as it is at modification stamp {@code stamp}. */
    public void markInSyncAt(long stamp) {
        syncedStamp = stamp;
    }

    /**
     * Whether the buffer is known to equal the document at stamp {@code stamp}: neither side changed
     * since {@link #markInSyncAt} (which is only called after a push into the loaded buffer). Example: detached at stamp 42 and attached
     * again at 42, nothing changed; a {@code :bufdo} edit in between clears it, and an Eclipse edit
     * moves the stamp.
     */
    public boolean isInSyncAt(long stamp) {
        return stamp != NO_STAMP && syncedStamp == stamp;
    }

    /** Neovim changed the buffer, or it was made anew: it is not known to equal the document. */
    void markOutOfSync() {
        syncedStamp = NO_STAMP;
    }

    /** Whether the buffer exists in {@code session} (not yet created, or from a restarted Neovim, if not). */
    public boolean isOpenIn(NeovimSession session) {
        return session.buffers().owns(this);
    }
}
