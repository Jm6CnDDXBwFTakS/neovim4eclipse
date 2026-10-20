// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.junit.jupiter.api.Test;

class ListenerListTest {

    @Test
    void notifiesAllListenersInRegistrationOrder() {
        ListenerList<String> list = new ListenerList<>("test", Level.FINE);
        List<String> seen = new ArrayList<>();
        list.add(v -> seen.add("a:" + v));
        list.add(v -> seen.add("b:" + v));
        list.fire("x");
        assertEquals(List.of("a:x", "b:x"), seen);
    }

    @Test
    void aThrowingListenerDoesNotStopTheOthers() {
        ListenerList<String> list = new ListenerList<>("test", Level.FINE);
        List<String> seen = new ArrayList<>();
        list.add(v -> { throw new IllegalStateException("boom"); });
        list.add(seen::add);
        list.fire("x");
        assertEquals(List.of("x"), seen);
    }

    @Test
    void removedListenersAreNotNotified() {
        ListenerList<String> list = new ListenerList<>("test", Level.FINE);
        List<String> seen = new ArrayList<>();
        Consumer<String> listener = seen::add;
        list.add(listener);
        list.remove(listener);
        list.fire("x");
        assertEquals(List.of(), seen);
    }

    @Test
    void listenersMayUnregisterThemselvesWhileBeingNotified() {
        ListenerList<String> list = new ListenerList<>("test", Level.FINE);
        List<String> seen = new ArrayList<>();
        AtomicReference<Consumer<String>> self = new AtomicReference<>();
        self.set(v -> { seen.add(v); list.remove(self.get()); });
        list.add(self.get());
        list.fire("1");
        list.fire("2");
        assertEquals(List.of("1"), seen);
    }
}
