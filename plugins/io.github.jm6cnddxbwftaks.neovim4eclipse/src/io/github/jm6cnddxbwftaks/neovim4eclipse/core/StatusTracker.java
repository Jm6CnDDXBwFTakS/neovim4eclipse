// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem;

/**
 * The status side of a {@link NeovimSession}: the {@code showcmd} text, the last message (for the
 * editor's message line), the statusline, and the register of a macro being recorded. It turns the raw parts read from Neovim
 * into {@link StatusInfo} events and fires them only when something changed.
 *
 * <p>Two rules shape it:
 * <ul>
 *   <li>A message stays visible until the next key ({@link #clearMessage()}): Neovim reports it once,
 *       and the following snapshot no longer contains it.</li>
 *   <li>While Neovim waits for the rest of a command ({@code 2f}), it cannot be asked for
 *       {@code showcmd}; the text is then built here from the keys sent ({@link #showPendingKey}).</li>
 * </ul>
 *
 * <p>Thread-safe in the way the session needs: written on the input thread, read from any thread.
 * Listeners are called on the thread that made the change.
 */
final class StatusTracker {

    private static final Logger LOG = Logger.getLogger(StatusTracker.class.getName());

    /** Which optional status bar parts to ask Neovim for. */
    private volatile Set<StatusItem> items = StatusItem.defaults();
    /** The message shown in the editor: kept until the next key is processed. */
    private volatile String currentMessage = "";
    /** Whether {@link #currentMessage} is an error. */
    private volatile boolean currentError;
    /** Last {@code showcmd} text read from Neovim (extended by keys that leave Neovim waiting). */
    private volatile String lastShowcmd = "";
    private volatile StatusInfo lastStatus = StatusInfo.EMPTY;
    /** Register of the macro being recorded ({@code qa} ... {@code q}), or "" if none. */
    private volatile String recordingRegister = "";

    private final ListenerList<StatusInfo> statusListeners = new ListenerList<>("status", Level.FINE);
    private final ListenerList<String> recordingListeners = new ListenerList<>("recording", Level.FINE);

    Set<StatusItem> items() {
        return items;
    }

    void setItems(Set<StatusItem> items) {
        this.items = Set.copyOf(items);
    }

    /**
     * Clears the message: a message stays visible until the next key. Listeners hear of it at once, so
     * that the same message given again by that key (a second {@code n} with no match) shows again.
     */
    void clearMessage() {
        if (!currentMessage.isEmpty()) {
            currentMessage = "";
            currentError = false;
            fire(new StatusInfo(lastStatus.statusline(), lastStatus.cmd(), "", false));
        }
    }

    /** Publishes the parts read from Neovim; an empty message keeps the current one. */
    void publish(StatusInfo parsed) {
        if (!parsed.message().isEmpty()) {
            currentMessage = parsed.message();
            currentError = parsed.error();
        }
        lastShowcmd = parsed.cmd();
        fire(new StatusInfo(parsed.statusline(), parsed.cmd(), currentMessage, currentError));
    }

    /**
     * Neovim is waiting for the rest of a command, so no API call can be made to ask for
     * {@code showcmd}; the status bar instead shows what is known: the previous pending text plus the
     * key just sent ({@code 2} then {@code f} shows {@code 2f}).
     */
    void showPendingKey(String keys) {
        if (keys.length() == 1 && items.contains(StatusItem.CMD)) {
            lastShowcmd = lastShowcmd + keys;
            fire(new StatusInfo(lastStatus.statusline(), lastShowcmd, currentMessage, currentError));
        }
    }

    private void fire(StatusInfo info) {
        if (!info.equals(lastStatus)) {
            lastStatus = info;
            statusListeners.fire(info);
        }
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

    void addRecordingListener(Consumer<String> listener) {
        recordingListeners.add(listener);
    }

    void removeRecordingListener(Consumer<String> listener) {
        recordingListeners.remove(listener);
    }
}
