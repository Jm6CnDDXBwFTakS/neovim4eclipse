// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FloatWindow;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HostCommand;

/**
 * The focus router without Neovim: each focus event reaches the one target that has the focus,
 * none while no target has it, and a target that gave the focus away cannot take it from the next.
 */
class FocusRouterTest {

    /** Records the modes it hears, under a name. */
    private static final class Recorder implements FocusTarget {
        final List<String> heard = new ArrayList<>();

        @Override
        public void onMode(String mode) {
            heard.add(mode);
        }
    }

    @Test
    void eventsGoToTheTargetWithTheFocusOnly() {
        FocusRouter router = new FocusRouter();
        Recorder code = new Recorder();
        Recorder page = new Recorder();
        router.focus(code);
        router.mode.accept("n");
        router.focus(page);
        router.mode.accept("v");
        assertEquals(List.of("n"), code.heard);
        assertEquals(List.of("v"), page.heard);
    }

    @Test
    void withoutATargetAnEventGoesNowhere() {
        FocusRouter router = new FocusRouter();
        router.mode.accept("n"); // no target yet: nothing to fail
        Recorder code = new Recorder();
        router.focus(code);
        router.release(code);
        router.mode.accept("i");
        assertEquals(List.of(), code.heard);
        assertNull(router.current());
    }

    /** A page that closes after the code took the focus back leaves the code with it. */
    @Test
    void releasingATargetWithoutTheFocusChangesNothing() {
        FocusRouter router = new FocusRouter();
        Recorder code = new Recorder();
        Recorder page = new Recorder();
        router.focus(page);
        router.focus(code);
        router.release(page);
        assertSame(code, router.current());
    }

    /** A target that throws is logged; the next event still reaches it. */
    @Test
    void aThrowingTargetDoesNotStopLaterEvents() {
        FocusRouter router = new FocusRouter();
        List<String> heard = new ArrayList<>();
        router.focus(new FocusTarget() {
            @Override
            public void onMode(String mode) {
                heard.add(mode);
                if (mode.equals("n")) {
                    throw new IllegalStateException("expected by the test");
                }
            }
        });
        try (ExpectedLog log = ExpectedLog.of(FocusRouter.class)) {
            router.mode.accept("n");
            log.assertLogged(Level.WARNING, "The mode event failed");
        }
        router.mode.accept("i");
        assertEquals(List.of("n", "i"), heard);
    }

    /** Every focus event has its own method; a target takes only those it overrides. */
    @Test
    void eachEventReachesItsOwnMethod() {
        FocusRouter router = new FocusRouter();
        List<String> heard = new ArrayList<>();
        router.focus(new FocusTarget() {
            @Override public void onRecording(String register) { heard.add("recording " + register); }
            @Override public void onHostCommand(HostCommand command) { heard.add("command"); }
            @Override public void onFloatingWindows(List<FloatWindow> windows) { heard.add("floats " + windows.size()); }
        });
        router.recording.accept("q");
        router.hostCommand.accept(HostCommand.of("quit", false, List.of()));
        router.floatingWindows.accept(List.of());
        router.mode.accept("n"); // not overridden: the empty default
        assertEquals(List.of("recording q", "command", "floats 0"), heard);
    }

    /** A command whose origin the rule says is another part's is dropped; one without is passed on. */
    @Test
    void aCommandFromAnotherPartsWindowIsDropped() {
        FocusRouter router = new FocusRouter();
        List<Integer> heard = new ArrayList<>();
        router.focus(new FocusTarget() {
            @Override public void onHostCommand(HostCommand command) { heard.add(command.origin()); }
        });
        router.setCommandRule(c -> c.origin() != 1004);
        HostCommand quit = HostCommand.of("quit", false, List.of());
        router.hostCommand.accept(quit.from(1004));
        router.hostCommand.accept(quit.from(1001));
        router.hostCommand.accept(quit);
        assertEquals(List.of(1001, HostCommand.NO_WINDOW), heard);
    }
}
