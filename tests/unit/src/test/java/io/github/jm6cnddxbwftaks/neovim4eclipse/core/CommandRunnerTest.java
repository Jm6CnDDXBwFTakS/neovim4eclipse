// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog;

/**
 * Command running without Neovim: a fake records the calls in order, so each test reads as "for
 * this command, these calls reach Neovim". What a command prints is not the runner's business any
 * more: it arrives as message events ({@code StatusTrackerTest}, {@code MessageRouteTest}).
 */
class CommandRunnerTest {

    /** Records calls; what Neovim answers is set per test. */
    private static final class FakeNeovim implements CommandRunner.Neovim {
        final List<String> calls = new ArrayList<>();
        Exception typeFailure;
        /** Thrown by {@link #awaitIdle}: a command that is still running. */
        Exception idleFailure;

        @Override
        public void type(String keys) throws Exception {
            calls.add("type " + keys);
            if (typeFailure != null) {
                throw typeFailure;
            }
        }

        @Override
        public void awaitIdle(Duration timeout) throws Exception {
            calls.add("awaitIdle " + timeout.toMillis());
            if (idleFailure != null) {
                throw idleFailure;
            }
        }

        @Override
        public void normalWithoutMappings(String keys) {
            calls.add("normal " + keys);
        }

        @Override
        public void clearMessages() {
            calls.add("clearMessages");
        }

        @Override
        public void syncBuffer() {
            calls.add("syncBuffer");
        }

        @Override
        public void syncModeAndCursor() {
            calls.add("syncModeAndCursor");
        }
    }

    private final FakeNeovim nvim = new FakeNeovim();
    private final CommandRunner runner = new CommandRunner(nvim, Duration.ofMillis(5000));

    /**
     * A command is typed as it was written, without {@code :silent} (which would hide its messages),
     * and the refresh waits until Neovim has run it.
     */
    @Test
    void runClearsTheLastMessagesTypesTheCommandWaitsAndRefreshes() throws Exception {
        runner.run("echo 'hello'");

        assertEquals(List.of("clearMessages", "type :echo 'hello'<CR>", "awaitIdle 5000", "syncBuffer",
                "syncModeAndCursor"), nvim.calls);
    }

    @Test
    void theDefaultWaitIsLongEnoughForAMake() {
        assertEquals(Duration.ofSeconds(60), CommandRunner.COMMAND_TIMEOUT);
    }

    @Test
    void aCommandStillRunningAfterTheTimeoutIsLoggedAndEclipseIsStillRefreshed() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(CommandRunner.class)) {
            nvim.idleFailure = new TimeoutException();

            runner.run("!sleep 100");

            assertEquals(List.of("syncBuffer", "syncModeAndCursor"),
                    nvim.calls.subList(nvim.calls.size() - 2, nvim.calls.size()));
            log.assertLogged(Level.WARNING, "still running");
        }
    }

    @Test
    void aFailureToTypeIsPassedOnAndNothingIsRefreshed() {
        nvim.typeFailure = new IllegalStateException("connection lost");

        Exception e = assertThrows(IllegalStateException.class, () -> runner.run("echo 1"));

        assertSame(nvim.typeFailure, e);
        assertEquals(List.of("clearMessages", "type :echo 1<CR>"), nvim.calls);
    }

    /** Its messages belong to the key that asked for it ({@code gd}), so they are not cleared. */
    @Test
    void normalRunsWithoutMappingsThenRefreshes() throws Exception {
        runner.normal("gd");

        assertEquals(List.of("normal gd", "syncBuffer", "syncModeAndCursor"), nvim.calls);
    }

    @Test
    void searchTypesTheEscapedPatternThenRefreshes() throws Exception {
        runner.search("a/b", false);

        assertEquals(List.of("clearMessages", "type /a\\/b<CR>", "awaitIdle 5000", "syncBuffer",
                "syncModeAndCursor"), nvim.calls);
    }

    /** A search line the user opened in Neovim is answered in place, keeping a pending operator. */
    @Test
    void answeringASearchLineReplacesItsTextAndRuns() throws Exception {
        runner.answerSearch("a/b", false);

        assertEquals(List.of("clearMessages", "type <C-u>a\\/b<CR>", "awaitIdle 5000", "syncBuffer",
                "syncModeAndCursor"), nvim.calls);
    }
}
