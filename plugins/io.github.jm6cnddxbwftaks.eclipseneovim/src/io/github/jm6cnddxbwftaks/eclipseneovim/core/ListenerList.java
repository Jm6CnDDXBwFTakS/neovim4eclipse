// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe listener registry that isolates failures: one throwing listener never prevents
 * the others from being notified.
 *
 * <p>Notifications happen on Neovim RPC/input threads; listeners are responsible for hopping to
 * the SWT thread themselves.
 */
final class ListenerList<T> {

    private static final Logger LOG = Logger.getLogger(ListenerList.class.getName());

    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();
    private final String name;
    private final Level failureLevel;

    /**
     * @param name         used in log messages
     * @param failureLevel level for listener exceptions; use FINE where failures are expected
     *                     (e.g. a listener whose SWT widget was disposed after detach)
     */
    ListenerList(String name, Level failureLevel) {
        this.name = name;
        this.failureLevel = failureLevel;
    }

    void add(Consumer<T> listener) {
        listeners.add(listener);
    }

    void remove(Consumer<T> listener) {
        listeners.remove(listener);
    }

    void fire(T value) {
        for (Consumer<T> listener : listeners) {
            try {
                listener.accept(value);
            } catch (Exception e) {
                LOG.log(failureLevel, name + " listener failed", e);
            }
        }
    }
}
