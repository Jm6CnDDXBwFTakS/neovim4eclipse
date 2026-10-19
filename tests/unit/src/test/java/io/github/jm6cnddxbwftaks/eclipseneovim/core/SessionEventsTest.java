// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Each kind of event reaches only its own listeners. (Order, failures and removal while firing are
 * {@link ListenerList}'s job, tested in {@code ListenerListTest}.)
 */
class SessionEventsTest {

    private final SessionEvents events = new SessionEvents();
    private final List<String> heard = new ArrayList<>();

    private final Consumer<String> mode = m -> heard.add("mode " + m);
    private final Consumer<BufferChange> buffer = c -> heard.add("buffer " + c.newLines());
    private final Consumer<CursorPos> cursor = p -> heard.add("cursor " + p.line1Based() + ":" + p.col0Based());
    private final Consumer<VisualSelection> selection = s -> heard.add("selection " + s.active());
    private final Consumer<IdeAction> ide = a -> heard.add("ide " + a.name());

    @BeforeEach
    void registerOneListenerOfEachKind() {
        events.addModeListener(mode);
        events.addBufferChangeListener(buffer);
        events.addCursorListener(cursor);
        events.addSelectionListener(selection);
        events.addIdeActionListener(ide);
    }

    private void fireOneOfEach() {
        events.mode("i");
        events.bufferChange(new BufferChange(0, 1, List.of("x")));
        events.cursor(new CursorPos(2, 3));
        events.selection(VisualSelection.none());
        events.ideAction(new IdeAction("definition", 1));
    }

    @Test
    void eachEventReachesItsOwnListener() {
        fireOneOfEach();

        assertEquals(List.of("mode i", "buffer [x]", "cursor 2:3", "selection false",
                "ide definition"), heard);
    }

    @Test
    void removingAListenerStopsOnlyThatKind() {
        events.removeModeListener(mode);
        events.removeCursorListener(cursor);

        fireOneOfEach();

        assertEquals(List.of("buffer [x]", "selection false", "ide definition"), heard);
    }

    @Test
    void removingEveryListenerSilencesAllEvents() {
        events.removeModeListener(mode);
        events.removeBufferChangeListener(buffer);
        events.removeCursorListener(cursor);
        events.removeSelectionListener(selection);
        events.removeIdeActionListener(ide);

        fireOneOfEach();

        assertEquals(List.of(), heard);
    }
}
