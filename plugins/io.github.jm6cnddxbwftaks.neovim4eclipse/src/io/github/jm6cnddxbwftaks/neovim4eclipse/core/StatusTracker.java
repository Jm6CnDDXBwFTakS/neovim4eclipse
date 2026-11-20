// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewContent;

/**
 * The status side of a {@link NeovimSession}: each editor window's status line, the register of a
 * macro being recorded, and what a page editor shows. It fires each only when it changed.
 *
 * <p>Neovim's messages and its pending command ({@code showcmd}) are not here: Neovim draws them
 * itself, in ui2's windows ({@code LuaScripts.UI2}), which the editor draws as floats.
 *
 * <ul>
 *   <li>A window's status line ({@code LuaScripts.STATUS_LINE}), which the editor of that window
 *       draws under its text ({@code editor/StatusLineRow}): the current window's with each key's
 *       state snapshot, every window's when Neovim sends them ({@code eclipse_status_lines});
 *       {@link #publishStatusLine}.</li>
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
    /** Each window's line last passed to the listeners, by window id; guarded by {@code this}. */
    private final Map<Integer, GridSnapshot> lastLines = new HashMap<>();
    /** Register of the macro being recorded ({@code qa} ... {@code q}), or "" if none. */
    private volatile String recordingRegister = "";
    private final ListenerList<GridSnapshot> statusLineListeners = new ListenerList<>("status line", Level.FINE);
    private final ListenerList<ViewContent> viewListeners = new ListenerList<>("view content", Level.FINE);
    private final ListenerList<String> recordingListeners = new ListenerList<>("recording", Level.FINE);

    Set<StatusItem> items() {
        return items;
    }

    void setItems(Set<StatusItem> items) {
        this.items = Set.copyOf(items);
    }

    /**
     * A window's status line as Neovim evaluated it ({@code util/StatusLines}), passed on when it
     * differs from the one last passed for that window. Example: a key that moves the cursor
     * changes the ruler part of the current window's line, and only that line is passed on.
     */
    void publishStatusLine(GridSnapshot line) {
        synchronized (this) {
            if (line.equals(lastLines.get(line.window()))) {
                return;
            }
            lastLines.put(line.window(), line);
        }
        statusLineListeners.fire(line);
    }

    /** Window {@code window}'s line last passed on, or {@code null} before the first. Any thread. */
    synchronized GridSnapshot statusLine(int window) {
        return lastLines.get(window);
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

    void addStatusLineListener(Consumer<GridSnapshot> listener) {
        statusLineListeners.add(listener);
    }

    void removeStatusLineListener(Consumer<GridSnapshot> listener) {
        statusLineListeners.remove(listener);
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
