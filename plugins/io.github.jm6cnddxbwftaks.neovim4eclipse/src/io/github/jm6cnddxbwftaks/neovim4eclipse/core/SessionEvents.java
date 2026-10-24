// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.function.Consumer;
import java.util.logging.Level;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CmdlineEvent;

/**
 * The session's listeners, one {@link ListenerList} per kind of event: mode, buffer change, cursor,
 * visual selection and IDE action. (Status bar and recording listeners are in {@link StatusTracker},
 * which also decides when those change.)
 *
 * <p>{@link StateSync} fires the first four through {@link StateSync.Listeners}; IDE actions come
 * straight from {@link NotificationBridge}. {@link NeovimSession}'s public {@code add...Listener} and
 * {@code remove...Listener} methods pass through to this class.
 *
 * <p>Listeners are called on a session thread (the input thread or an RPC thread) and must hop to
 * the UI thread themselves. A throwing listener is logged and does not stop the others.
 */
final class SessionEvents implements StateSync.Listeners {

    private final ListenerList<String> modeListeners = new ListenerList<>("mode", Level.FINE);
    private final ListenerList<BufferChange> bufferChangeListeners = new ListenerList<>("buffer change", Level.WARNING);
    private final ListenerList<CursorPos> cursorListeners = new ListenerList<>("cursor", Level.WARNING);
    private final ListenerList<VisualSelection> selectionListeners = new ListenerList<>("selection", Level.WARNING);
    private final ListenerList<IdeAction> ideActionListeners = new ListenerList<>("IDE action", Level.WARNING);
    private final ListenerList<CmdlineEvent> userCmdlineListeners = new ListenerList<>("user command line", Level.WARNING);

    // ---- firing ----

    @Override
    public void mode(String mode) {
        modeListeners.fire(mode);
    }

    @Override
    public void cursor(CursorPos cursor) {
        cursorListeners.fire(cursor);
    }

    @Override
    public void selection(VisualSelection selection) {
        selectionListeners.fire(selection);
    }

    @Override
    public void bufferChange(BufferChange change) {
        bufferChangeListeners.fire(change);
    }

    /** A key mapped in Neovim asks the IDE to act (see {@link IdeAction}). */
    void ideAction(IdeAction action) {
        ideActionListeners.fire(action);
    }

    /** The user opened a command line in Neovim that the editor's box did not (see {@code CmdlineWatcher}). */
    void userCmdline(CmdlineEvent event) {
        userCmdlineListeners.fire(event);
    }

    // ---- registration ----

    void addModeListener(Consumer<String> listener) {
        modeListeners.add(listener);
    }

    void removeModeListener(Consumer<String> listener) {
        modeListeners.remove(listener);
    }

    void addBufferChangeListener(Consumer<BufferChange> listener) {
        bufferChangeListeners.add(listener);
    }

    void removeBufferChangeListener(Consumer<BufferChange> listener) {
        bufferChangeListeners.remove(listener);
    }

    void addCursorListener(Consumer<CursorPos> listener) {
        cursorListeners.add(listener);
    }

    void removeCursorListener(Consumer<CursorPos> listener) {
        cursorListeners.remove(listener);
    }

    void addSelectionListener(Consumer<VisualSelection> listener) {
        selectionListeners.add(listener);
    }

    void removeSelectionListener(Consumer<VisualSelection> listener) {
        selectionListeners.remove(listener);
    }

    void addIdeActionListener(Consumer<IdeAction> listener) {
        ideActionListeners.add(listener);
    }

    void removeIdeActionListener(Consumer<IdeAction> listener) {
        ideActionListeners.remove(listener);
    }

    void addUserCmdlineListener(Consumer<CmdlineEvent> listener) {
        userCmdlineListeners.add(listener);
    }

    void removeUserCmdlineListener(Consumer<CmdlineEvent> listener) {
        userCmdlineListeners.remove(listener);
    }
}
