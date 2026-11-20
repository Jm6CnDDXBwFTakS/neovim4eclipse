// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewContent;

/**
 * The status side of a {@link NeovimSession}: Neovim's {@code 'statusline'}, the register of a
 * macro being recorded, and what a page editor shows. It fires each only when it changed.
 *
 * <p>Neovim's messages and its pending command ({@code showcmd}) are not here: Neovim draws them
 * itself, in ui2's windows ({@code LuaScripts.UI2}), which the editor draws as floats.
 *
 * <ul>
 *   <li>The statusline is pulled with each key's state snapshot ({@link #publishStatusline}),
 *       when the status bar shows it ({@link StatusItem#STATUSLINE}).</li>
 *   <li>The recording register comes with the same snapshot ({@link #updateRecording}).</li>
 *   <li>A page editor's content ({@code :messages}, {@code :checkhealth}, {@code :help}) comes from
 *       the bridge, on the RPC reader thread ({@link #onViewContent}).</li>
 * </ul>
 *
 * <p>Thread-safe: the snapshot thread and the reader thread call it; listeners are called on the
 * calling thread, outside the lock.
 */
final class StatusTracker {

    private static final Logger LOG = Logger.getLogger(StatusTracker.class.getName());

    /** Which optional status bar parts to ask Neovim for. */
    private volatile Set<StatusItem> items = StatusItem.defaults();
    private volatile StatusInfo lastStatus = StatusInfo.EMPTY;
    /** Register of the macro being recorded ({@code qa} ... {@code q}), or "" if none. */
    private volatile String recordingRegister = "";
    /**
     * The statusline label's width in characters, the {@code maxwidth} Neovim evaluates the
     * {@code 'statusline'} at; the status bar sets it as it lays out its label. 0 until then: no
     * label, nothing to evaluate.
     */
    private volatile int statuslineColumns;

    private final ListenerList<StatusInfo> statusListeners = new ListenerList<>("status", Level.FINE);
    private final ListenerList<ViewContent> viewListeners = new ListenerList<>("view content", Level.FINE);
    private final ListenerList<String> recordingListeners = new ListenerList<>("recording", Level.FINE);

    Set<StatusItem> items() {
        return items;
    }

    int statuslineColumns() {
        return statuslineColumns;
    }

    void setStatuslineColumns(int columns) {
        statuslineColumns = Math.max(1, columns);
    }

    void setItems(Set<StatusItem> items) {
        this.items = Set.copyOf(items);
    }

    /** The statusline read with the state snapshot ("" when the status bar does not show it). */
    void publishStatusline(String text) {
        StatusInfo info = new StatusInfo(text);
        synchronized (this) {
            if (info.equals(lastStatus)) {
                return;
            }
            lastStatus = info;
        }
        statusListeners.fire(info);
    }

    /** The status last passed to the listeners: what Neovim shows now. Any thread. */
    StatusInfo current() {
        return lastStatus;
    }

    // ---- macro recording ----

    /** Register being recorded into, or "" when not recording. */
    String recordingRegister() {
        return recordingRegister;
    }

    /** Records the register from the latest snapshot and notifies listeners if it changed. */
    void updateRecording(String register) {
        if (!register.equals(recordingRegister)) {
            recordingRegister = register;
            LOG.fine(() -> register.isEmpty() ? "macro recording stopped" : "recording macro into @" + register);
            recordingListeners.fire(register);
        }
    }

    // ---- listeners ----

    void addStatusListener(Consumer<StatusInfo> listener) {
        statusListeners.add(listener);
    }

    void removeStatusListener(Consumer<StatusInfo> listener) {
        statusListeners.remove(listener);
    }

    /**
     * Listens for what a page editor shows ({@code :messages}, {@code :checkhealth},
     * {@code :help}); called on the RPC reader thread.
     */
    void addViewContentListener(Consumer<ViewContent> listener) {
        viewListeners.add(listener);
    }

    void removeViewContentListener(Consumer<ViewContent> listener) {
        viewListeners.remove(listener);
    }

    /** Content for a page editor: the message history, a {@code :checkhealth} report, a {@code :help} page. */
    void onViewContent(ViewContent content) {
        viewListeners.fire(content);
    }

    void addRecordingListener(Consumer<String> listener) {
        recordingListeners.add(listener);
    }

    void removeRecordingListener(Consumer<String> listener) {
        recordingListeners.remove(listener);
    }
}
