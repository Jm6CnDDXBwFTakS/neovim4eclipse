// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Incoming;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog;

/** What Neovim sends before the session listens is handed on first, in order; requests then fail. */
class EarlyIncomingTest {

    /** Records what reaches it, as {@code method params}. */
    private static class Recorder implements Incoming {
        final List<String> got = new ArrayList<>();

        @Override
        public void notification(String method, List<?> params) {
            got.add(method + " " + params);
        }

        @Override
        public Object request(String method, List<?> params) {
            got.add("request " + method);
            return "answer";
        }
    }

    @Test
    void everyNotificationBeforeTheSessionIsHandedOnFirstInOrder() {
        EarlyIncoming early = new EarlyIncoming();
        // As at a start: the first window's place, then a float's size, before the session is there.
        early.notification("redraw", List.of(List.of("win_pos", List.of(2, 1000))));
        early.notification("redraw", List.of(List.of("grid_resize", List.of(3, 96, 32))));
        early.notification("nvim_buf_changedtick_event", List.of(1, 2));
        Recorder session = new Recorder();
        early.deliverTo(session);
        assertEquals(List.of(), session.got, "handed on with the next notification, on the reader thread");
        early.notification("redraw", List.of(List.of("flush", List.of())));
        assertEquals(List.of("redraw [[win_pos, [2, 1000]]]", "redraw [[grid_resize, [3, 96, 32]]]",
                "nvim_buf_changedtick_event [1, 2]", "redraw [[flush, []]]"), session.got);
        early.notification("redraw", List.of());
        assertEquals(5, session.got.size(), "the kept ones only once");
    }

    @Test
    void aRequestBeforeTheSessionIsAnsweredWithAnErrorAndAfterByTheSessionAfterTheKeptOnes() throws Exception {
        EarlyIncoming early = new EarlyIncoming();
        assertThrows(IllegalStateException.class, () -> early.request("eclipse_write", List.of()));
        early.notification("redraw", List.of(List.of("win_pos", List.of(2, 1000))));
        Recorder session = new Recorder();
        early.deliverTo(session);
        assertEquals("answer", early.request("eclipse_write", List.of()));
        assertEquals(List.of("redraw [[win_pos, [2, 1000]]]", "request eclipse_write"), session.got,
                "the kept notification first");
    }

    /** A kept notification whose handler fails costs only itself: the rest and the next still arrive. */
    @Test
    void aKeptNotificationThatFailsCostsOnlyItself() {
        EarlyIncoming early = new EarlyIncoming();
        early.notification("broken", List.of());
        early.notification("redraw", List.of(List.of("hl_attr_define", List.of(1))));
        Recorder session = new Recorder() {
            @Override
            public void notification(String method, List<?> params) {
                if (method.equals("broken")) {
                    throw new IllegalStateException("a handler's bug (expected by the test)");
                }
                super.notification(method, params);
            }
        };
        early.deliverTo(session);
        try (ExpectedLog log = ExpectedLog.of(EarlyIncoming.class)) {
            early.notification("redraw", List.of(List.of("flush", List.of())));
            log.assertLogged(Level.WARNING, "Notification broken from Neovim's start failed");
        }
        assertEquals(List.of("redraw [[hl_attr_define, [1]]]", "redraw [[flush, []]]"), session.got);
    }
}
