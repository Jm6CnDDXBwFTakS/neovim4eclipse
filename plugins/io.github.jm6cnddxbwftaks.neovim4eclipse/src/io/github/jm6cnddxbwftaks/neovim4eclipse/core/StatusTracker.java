// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.MessageEvent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.MessageRoute;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem;

/**
 * The status side of a {@link NeovimSession}: the {@code showcmd} text, the message for the
 * editor's message line, the output for the popup, the statusline, and the register of a macro
 * being recorded. It turns what Neovim reports into {@link StatusInfo} and {@link CommandOutput}
 * events and fires them only when something changed.
 *
 * <p>Where each part comes from:
 * <ul>
 *   <li>Messages and {@code showcmd} are pushed by Neovim as UI events ({@code ext_messages}, see
 *       {@link MessageEvent}), on the RPC reader thread. {@link MessageRoute} decides what goes to
 *       the message line and what to the popup; what arrived is passed on at each {@code flush},
 *       the end of one of Neovim's redraws, so that a search's echo and its {@code [1/5]} count,
 *       sent together, show as one.</li>
 *   <li>The statusline is pulled with each key's state snapshot ({@link #publishStatusline}).</li>
 * </ul>
 *
 * <p>A message stays visible until the next key ({@link #clearMessage()}), as in Vim. The pending
 * command shows while Neovim waits for the rest of it: after {@code 2} then {@code f}, Neovim sends
 * {@code msg_showcmd "2f"} even though it answers almost no request then.
 *
 * <p>Thread-safe: messages arrive on the RPC reader thread, keys clear them on the input thread.
 * The state is guarded by {@code this}; listeners are called outside the lock, on the thread that
 * made the change.
 */
final class StatusTracker {

    private static final Logger LOG = Logger.getLogger(StatusTracker.class.getName());

    /** Which optional status bar parts to ask Neovim for. */
    private volatile Set<StatusItem> items = StatusItem.defaults();
    /** The messages of the current command; guarded by {@code this}. */
    private final MessageRoute route = new MessageRoute();
    /** The latest {@code msg_showcmd} text; guarded by {@code this}. */
    private String showcmd = "";
    /** The latest statusline from the snapshot; guarded by {@code this}. */
    private String statusline = "";
    /** The output last passed to the output listeners, so it is passed once; guarded by {@code this}. */
    private String lastOutput = "";
    private volatile StatusInfo lastStatus = StatusInfo.EMPTY;
    /** Register of the macro being recorded ({@code qa} ... {@code q}), or "" if none. */
    private volatile String recordingRegister = "";

    private final ListenerList<StatusInfo> statusListeners = new ListenerList<>("status", Level.FINE);
    private final ListenerList<CommandOutput> outputListeners = new ListenerList<>("output", Level.FINE);
    private final ListenerList<String> recordingListeners = new ListenerList<>("recording", Level.FINE);

    Set<StatusItem> items() {
        return items;
    }

    void setItems(Set<StatusItem> items) {
        this.items = Set.copyOf(items);
    }

    /**
     * A new command starts (a key, or a line from the box): its messages replace the last
     * command's, which are hidden now. Listeners hear of it at once, so that the same message given
     * again by the next key (a second {@code n} with no match) shows again.
     */
    void clearMessage() {
        StatusInfo info;
        synchronized (this) {
            route.clear();
            lastOutput = "";
            info = status(MessageRoute.View.NONE);
        }
        fire(info);
    }

    /** Neovim showed command line {@code text}, such as {@code ":ls"}: the title for its output. */
    synchronized void commandLine(String text) {
        route.commandLine(text);
    }

    /** One message event from Neovim; see the class comment. */
    void onMessageEvent(MessageEvent event) {
        StatusInfo info = null;
        CommandOutput output = null;
        synchronized (this) {
            switch (event) {
                case MessageEvent.Show show -> route.add(show);
                case MessageEvent.History history -> route.add(history);
                case MessageEvent.Showcmd cmd -> showcmd = cmd.text();
                case MessageEvent.Flush flush -> {
                    MessageRoute.View view = route.view();
                    info = status(view);
                    if (view.hasOutput() && !view.output().equals(lastOutput)) {
                        lastOutput = view.output();
                        output = new CommandOutput(view.title(), view.output());
                    }
                }
            }
        }
        if (info != null) {
            fire(info);
        }
        if (output != null) {
            CommandOutput shown = output;
            LOG.fine(() -> "output for the popup: " + shown.title());
            outputListeners.fire(shown);
        }
    }

    /** The statusline read with the state snapshot ("" when the status bar does not show it). */
    void publishStatusline(String text) {
        StatusInfo info;
        synchronized (this) {
            statusline = text;
            info = status(route.view());
        }
        fire(info);
    }

    /** The status for {@code view}: the message line shows its line (the popup, its output). */
    private StatusInfo status(MessageRoute.View view) {
        String cmd = items.contains(StatusItem.CMD) ? showcmd : "";
        return new StatusInfo(statusline, cmd, view.line(), view.error());
    }

    private void fire(StatusInfo info) {
        synchronized (this) {
            if (info.equals(lastStatus)) {
                return;
            }
            lastStatus = info;
        }
        statusListeners.fire(info);
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

    void addOutputListener(Consumer<CommandOutput> listener) {
        outputListeners.add(listener);
    }

    void removeOutputListener(Consumer<CommandOutput> listener) {
        outputListeners.remove(listener);
    }

    void addRecordingListener(Consumer<String> listener) {
        recordingListeners.add(listener);
    }

    void removeRecordingListener(Consumer<String> listener) {
        recordingListeners.remove(listener);
    }
}
