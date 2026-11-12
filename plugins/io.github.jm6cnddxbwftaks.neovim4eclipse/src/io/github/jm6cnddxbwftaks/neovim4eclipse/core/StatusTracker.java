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
 * The state is guarded by {@code this}; listeners are called outside that lock, on the thread that
 * made the change.
 *
 * <p>Why a status is computed, compared and recorded in one locked step, and numbered: two threads
 * change the status at once, say the input thread clearing the message for a key while the reader
 * thread passes on Neovim's {@code E486}. If each computed its status in one locked block and
 * recorded it in another, the clear could be computed first and recorded last, and the status
 * bar would show an empty message while Neovim shows the error. Recording in the same block puts
 * the statuses in one order; the {@link Delivery} number keeps the listeners to that order: a
 * thread that comes to deliver after a newer status went out drops its stale one.
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
    /** The number of the status last recorded in {@link #lastStatus}; guarded by {@code this}. */
    private long recordedSeq;
    /** The number of the status last passed to the listeners; guarded by {@link #deliveryLock}. */
    private long deliveredSeq;
    /** Serializes the calls to the status listeners, so that they hear statuses in recorded order. */
    private final Object deliveryLock = new Object();
    /** Register of the macro being recorded ({@code qa} ... {@code q}), or "" if none. */
    private volatile String recordingRegister = "";

    private final ListenerList<StatusInfo> statusListeners = new ListenerList<>("status", Level.FINE);
    private final ListenerList<CommandOutput> outputListeners = new ListenerList<>("output", Level.FINE);
    private final ListenerList<String> recordingListeners = new ListenerList<>("recording", Level.FINE);

    /**
     * The statusline label's width in characters, the {@code maxwidth} Neovim evaluates the
     * {@code 'statusline'} at; the status bar sets it as it lays out its label. 0 until then: no
     * label, nothing to evaluate.
     */
    private volatile int statuslineColumns;

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

    /**
     * A new command starts (a key, or a line from the box): its messages replace the last
     * command's, which are hidden now. Listeners hear of it at once, so that the same message given
     * again by the next key (a second {@code n} with no match) shows again.
     */
    void clearMessage() {
        Delivery delivery;
        synchronized (this) {
            route.clear();
            lastOutput = "";
            delivery = record(status(MessageRoute.View.NONE));
        }
        deliver(delivery);
    }

    /** Neovim showed command line {@code text}, such as {@code ":ls"}: the title for its output. */
    synchronized void commandLine(String text) {
        route.commandLine(text);
    }

    /** One message event from Neovim; see the class comment. */
    void onMessageEvent(MessageEvent event) {
        Delivery delivery = null;
        CommandOutput output = null;
        synchronized (this) {
            switch (event) {
                case MessageEvent.Show show -> route.add(show);
                case MessageEvent.History history -> route.add(history);
                case MessageEvent.Showcmd cmd -> showcmd = cmd.text();
                case MessageEvent.Flush flush -> {
                    MessageRoute.View view = route.view();
                    delivery = record(status(view));
                    if (view.hasOutput() && !view.output().equals(lastOutput)) {
                        lastOutput = view.output();
                        output = new CommandOutput(view.title(), view.output());
                    }
                }
            }
        }
        deliver(delivery);
        if (output != null) {
            CommandOutput shown = output;
            LOG.fine(() -> "output for the popup: " + shown.title());
            outputListeners.fire(shown);
        }
    }

    /** The statusline read with the state snapshot ("" when the status bar does not show it). */
    void publishStatusline(String text) {
        Delivery delivery;
        synchronized (this) {
            statusline = text;
            delivery = record(status(route.view()));
        }
        deliver(delivery);
    }

    /** The status for {@code view}: the message line shows its line (the popup, its output). */
    private StatusInfo status(MessageRoute.View view) {
        String cmd = items.contains(StatusItem.CMD) ? showcmd : "";
        return new StatusInfo(statusline, cmd, view.line(), view.error());
    }

    /** A recorded status, numbered in the order it was recorded, waiting to reach the listeners. */
    record Delivery(long seq, StatusInfo info) {
    }

    /**
     * Records {@code info} as the current status if it differs from the last one. Called in the
     * same locked block that computed {@code info} (see the class comment); {@code synchronized}
     * is reentrant, so holding the lock already is fine.
     *
     * @return what to pass to {@link #deliver}, or {@code null} when nothing changed
     */
    synchronized Delivery record(StatusInfo info) {
        if (info.equals(lastStatus)) {
            return null;
        }
        lastStatus = info;
        return new Delivery(++recordedSeq, info);
    }

    /**
     * Passes a recorded status to the listeners, outside the state lock, unless a newer one went
     * out already. Example: the reader thread records {@code E486} (2) after the input thread
     * recorded a cleared message (1), and gets to deliver first: the input thread then drops 1,
     * and the listeners end on {@code E486}, the status {@link #current()} returns.
     *
     * <p>The listeners are called under {@link #deliveryLock}; they only hand the status on to the
     * UI thread ({@code asyncExec}), so another thread waits for no more than that.
     */
    void deliver(Delivery delivery) {
        if (delivery == null) {
            return;
        }
        synchronized (deliveryLock) {
            if (delivery.seq() <= deliveredSeq) {
                return;
            }
            deliveredSeq = delivery.seq();
            statusListeners.fire(delivery.info());
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

    /** The status last recorded (passed to the listeners, or about to be): what Neovim shows now. Any thread. */
    StatusInfo current() {
        return lastStatus;
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
