// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.jm6cnddxbwftaks.eclipseneovim.testing.ExpectedLog;

/**
 * Command running without Neovim: a fake records the calls in order, so each test reads as "for
 * this command, these calls reach Neovim and the listener gets this text".
 *
 * <p>The timeouts are milliseconds here instead of the real 60 s and 250 ms.
 */
class CommandRunnerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration POLL = Duration.ofMillis(5);

    /** Records calls; what Neovim answers is set per test. */
    private static final class FakeNeovim implements CommandRunner.Neovim {
        final List<String> calls = new ArrayList<>();
        /** Answers {@link #isBlocked()}; may look at {@link #calls}. */
        BooleanSupplier blocked = () -> false;
        /** What ending the capture answers; replace with an unfinished future for a running command. */
        CompletableFuture<Object> stopAnswer = CompletableFuture.completedFuture("");
        Object captureAnswer = "";
        Exception typeFailure;
        Exception captureFailure;

        @Override
        public void type(String keys) throws Exception {
            calls.add("type " + keys);
            if (typeFailure != null) {
                throw typeFailure;
            }
        }

        @Override
        public boolean isBlocked() {
            return blocked.getAsBoolean();
        }

        @Override
        public void startCapture() {
            calls.add("startCapture");
        }

        @Override
        public CompletableFuture<Object> stopCapture() {
            calls.add("stopCapture");
            return stopAnswer;
        }

        @Override
        public Object captureOutput(String cmd) throws Exception {
            calls.add("captureOutput " + cmd);
            if (captureFailure != null) {
                throw captureFailure;
            }
            return captureAnswer;
        }

        @Override
        public void normalWithoutMappings(String keys) {
            calls.add("normal " + keys);
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
    private final CommandRunner runner = new CommandRunner(nvim, TIMEOUT, POLL);
    private final List<String> outputs = new ArrayList<>();

    // ---- run: typed : commands ----

    @Test
    void runCapturesAroundTheTypedCommandAndPassesTheOutput() throws Exception {
        nvim.stopAnswer = CompletableFuture.completedFuture("hello");

        runner.run("echo 'hello'", outputs::add);

        assertEquals(List.of("startCapture", "type :silent echo 'hello'<CR>", "stopCapture",
                "syncBuffer", "syncModeAndCursor"), nvim.calls);
        assertEquals(List.of("hello"), outputs);
    }

    @Test
    void runWithoutAListenerDoesNotCapture() throws Exception {
        runner.run("s/foo/bar/", null);

        assertEquals(List.of("type :silent s/foo/bar/<CR>", "syncBuffer", "syncModeAndCursor"), nvim.calls);
    }

    @Test
    void blankOutputIsNotPassed() throws Exception {
        nvim.stopAnswer = CompletableFuture.completedFuture("\n  \n");

        runner.run("let g:x = 1", outputs::add);

        assertEquals(List.of(), outputs);
    }

    @Test
    void noCaptureWhileTheUsersOwnRedirRuns() throws Exception {
        runner.run("redir => g:mine", outputs::add);
        runner.run("echo 'kept'", outputs::add); // a capture would take the redirection over
        runner.run("redir END", outputs::add);
        nvim.stopAnswer = CompletableFuture.completedFuture("after");
        runner.run("echo 'after'", outputs::add); // captured again

        assertEquals(1, nvim.calls.stream().filter("startCapture"::equals).count());
        assertEquals(List.of("after"), outputs);
    }

    @Test
    void captureIsStoppedEvenWhenTypingFails() {
        nvim.typeFailure = new IllegalStateException("connection lost");

        Exception e = assertThrows(IllegalStateException.class, () -> runner.run("echo 1", outputs::add));

        assertSame(nvim.typeFailure, e);
        assertEquals(List.of("startCapture", "type :silent echo 1<CR>", "stopCapture"), nvim.calls);
    }

    @Test
    void aPressEnterPromptLeftByTheCommandIsDismissed() throws Exception {
        // Blocked right after typing, until the Esc arrives.
        nvim.blocked = () -> !nvim.calls.contains("type <Esc>");

        runner.run("ls", null);

        assertEquals(List.of("type :silent ls<CR>", "type <Esc>", "syncBuffer", "syncModeAndCursor"),
                nvim.calls);
    }

    @Test
    void aPromptWhileTheCommandRunsGetsAnEscAndTheOutputArrives() throws Exception {
        CompletableFuture<Object> stop = new CompletableFuture<>();
        nvim.stopAnswer = stop;
        // Not blocked right after typing; then the command stops at a prompt, and finishes on Esc.
        nvim.blocked = () -> {
            if (nvim.calls.contains("stopCapture") && !nvim.calls.contains("type <Esc>")) {
                return true;
            }
            if (nvim.calls.contains("type <Esc>")) {
                stop.complete("done");
            }
            return false;
        };

        runner.run("!make", outputs::add);

        assertTrue(nvim.calls.contains("type <Esc>"), nvim.calls.toString());
        assertEquals(List.of("done"), outputs);
    }

    @Test
    @Timeout(5) // without the timeout the runner would wait forever: fail instead of hanging the build
    void aCommandStillRunningAfterTheTimeoutGivesNoOutputButStillRefreshes() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(CommandRunner.class)) {
            CommandRunner quick = new CommandRunner(nvim, Duration.ofMillis(30), POLL);
            nvim.stopAnswer = new CompletableFuture<>(); // never answers

            long start = System.nanoTime();
            quick.run("!sleep 100", outputs::add);
            long tookMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

            assertEquals(List.of(), outputs);
            assertEquals(List.of("syncBuffer", "syncModeAndCursor"),
                    nvim.calls.subList(nvim.calls.size() - 2, nvim.calls.size()));
            assertTrue(tookMs >= 30 && tookMs < TIMEOUT.toMillis(), "waited " + tookMs + " ms");
            log.assertLogged(Level.WARNING, "command still running");
        }
    }

    // ---- capture: listing commands ----

    @Test
    void captureRunsWithoutTypingAndDropsTheLeadingNewline() throws Exception {
        nvim.captureAnswer = "\n:ls output";

        runner.capture("ls", outputs::add);

        assertEquals(List.of("captureOutput ls"), nvim.calls);
        assertEquals(List.of(":ls output"), outputs);
    }

    @Test
    void captureOfAFailingCommandPassesNeovimsErrorMessage() throws Exception {
        nvim.captureFailure = new RuntimeException("nvim_exec2: Vim(marks):E283: No marks matching \"q\"");

        runner.capture("marks q", outputs::add);

        assertEquals(List.of("E283: No marks matching \"q\""), outputs);
    }

    @Test
    void captureWhileTheUsersOwnRedirRunsTypesTheCommandAndPassesNothing() throws Exception {
        runner.run("redir @a", outputs::add);
        nvim.calls.clear();

        runner.capture("echo 'kept'", outputs::add);

        assertEquals(List.of("type :silent echo 'kept'<CR>", "syncModeAndCursor"), nvim.calls);
        assertEquals(List.of(), outputs);
    }

    // ---- normal! and search ----

    @Test
    void normalRunsWithoutMappingsThenRefreshes() throws Exception {
        runner.normal("gd");

        assertEquals(List.of("normal gd", "syncBuffer", "syncModeAndCursor"), nvim.calls);
    }

    @Test
    void searchTypesTheEscapedPatternThenRefreshes() throws Exception {
        runner.search("a/b", false);

        assertEquals(List.of("type /a\\/b<CR>", "syncBuffer", "syncModeAndCursor"), nvim.calls);
    }
}
