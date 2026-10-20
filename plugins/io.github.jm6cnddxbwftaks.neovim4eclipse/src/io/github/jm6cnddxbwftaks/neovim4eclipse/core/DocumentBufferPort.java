// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.List;
import java.util.function.Consumer;

/**
 * What keeping an Eclipse document that is <em>not</em> attached in step with its Neovim buffer
 * needs from the session ({@code sync/UnattachedDocumentSync}). {@link BufferPort} is the same for
 * the attached document.
 *
 * <h2>Why documents that are not attached change</h2>
 * Only one document is attached (Neovim has one window), but every open document keeps its buffer,
 * and Neovim can change any of them: {@code :bufdo %s/old/new/g} edits every buffer in turn,
 * {@code :argdo} and {@code :cdo} do the same for their lists, and a plug-in in the user's config
 * may call {@code nvim_buf_set_lines} on any buffer. The other way, Eclipse changes documents whose
 * editor is in the background: a rename refactoring edits every file that uses the name. Each such
 * change has to reach the other side, or the next edit there would be made at stale line numbers,
 * or the attach push would overwrite it.
 *
 * <p>An interface (rather than the whole {@link NeovimSession}) so that the sync class is tested
 * with a small fake in {@code tests/text}. {@link NeovimSession} implements it.
 */
public interface DocumentBufferPort {

    /**
     * Makes the text of {@code buffer} equal to {@code lines}, attached or not, changing only the
     * lines that differ (so undo history and marks of the others survive). Neovim's line events for
     * this write are not reported: they would echo it back. Blocking RPC: call on the session's
     * input thread, that is, from work given to {@link #runAsync}.
     *
     * @return {@code false}, having done nothing, when the buffer does not exist in this session
     *         (never attached, wiped, or from a Neovim that was restarted) or has not been loaded:
     *         the next attach loads it
     */
    boolean setDocumentBufferLines(DocumentBuffer buffer, List<String> lines) throws Exception;

    /**
     * Runs {@code work} on the input thread, after every key and command queued before it.
     *
     * @param what short description for the log, should {@code work} fail
     */
    void runAsync(String what, Runnable work);

    /**
     * Notifies {@code listener} (on the RPC thread, in Neovim's order) of each change Neovim makes to
     * {@code buffer} while it is not attached. One listener per buffer: a second one replaces the first.
     */
    void addUnattachedChangeListener(DocumentBuffer buffer, Consumer<BufferChange> listener);

    /** Removes {@code listener}, if it is still the one registered for {@code buffer}. */
    void removeUnattachedChangeListener(DocumentBuffer buffer, Consumer<BufferChange> listener);
}
