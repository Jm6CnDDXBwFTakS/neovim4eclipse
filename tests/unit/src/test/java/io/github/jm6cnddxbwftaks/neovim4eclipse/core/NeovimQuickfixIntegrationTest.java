// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.NeovimErrorException;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.QuickfixEntries;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * The session's half of the quickfix lists ({@link QuickfixPort}) while Neovim waits for the rest of
 * a command: after {@code r} (or {@code ]} of {@code ]d}, {@code "}, {@code q}) Neovim answers no API
 * call but a few, so a question asked then would hold the input thread, and the key that ends the
 * command with it, until {@link Rpc#TIMEOUT_SEC} (found 2026-10-29 in a macOS UI test: a marker
 * change during {@code ]d} cost 5 s). The session holds the question and asks it after that key.
 * What the Lua does with the list is {@code quickfix_spec.lua}. Skipped when no {@code nvim} is found.
 */
@Tag("integration")
class NeovimQuickfixIntegrationTest {

    private static NeovimSession start() throws Exception {
        TestNeovim.require();
        return NeovimSession.start(TestNeovim.PATH, List.of("--clean"));
    }

    @Test
    void theCurrentListIsAskedAfterTheKeyThatEndsACommand() throws Exception {
        try (NeovimSession s = start()) {
            s.inputAsync("r"); // Neovim waits for the character to replace with
            CompletableFuture<QuickfixEntries.Current> answer = s.currentQuickfixAsync();
            s.inputAsync("<Esc>");
            // Before, the question timed out (an ExecutionException here) and the <Esc> waited for it.
            assertNull(answer.get(TestWait.millis(), TimeUnit.MILLISECONDS), "no list of Eclipse's is current");
        }
    }

    /** A guard: before, this passed too, only 5 s later (Neovim ran the call once the key came). */
    @Test
    void aListSentWhileNeovimWaitsArrivesAfterTheKey() throws Exception {
        try (NeovimSession s = start()) {
            s.inputAsync("r");
            s.setQuickfixAsync(QuickfixEntries.Kind.PROBLEMS, "", QuickfixEntries.Mode.COMMAND, false, List.of());
            s.inputAsync("<Esc>");
            QuickfixEntries.Current current = s.currentQuickfixAsync().get(TestWait.millis(), TimeUnit.MILLISECONDS);
            assertEquals(QuickfixEntries.Kind.PROBLEMS, current == null ? null : current.kind(), "the list arrived");
        }
    }

    @Test
    void aQuestionHeldWhenNeovimClosesFails() throws Exception {
        CompletableFuture<QuickfixEntries.Current> answer;
        try (NeovimSession s = start()) {
            s.inputAsync("r");
            answer = s.currentQuickfixAsync(); // held, queued or being sent: unanswered at close
        }
        // Before, on one core, a question being sent as Neovim quit failed with the closed connection's
        // error instead: close asked Neovim to quit before it failed the open answers.
        ExecutionException e = assertThrows(ExecutionException.class, () -> answer.get(TestWait.millis(), TimeUnit.MILLISECONDS));
        assertEquals(IllegalStateException.class, e.getCause().getClass(), "Neovim is closed: " + e.getCause());
    }

    /**
     * Neovim's error reaches the caller as the cause, with no wrapper in between. Before, the session
     * handed on the ExecutionException its own wait had thrown, and get() wrapped that in another:
     * the cause was an ExecutionException. commandAsync waits until Neovim ran the command, so the
     * question after it always finds the broken function.
     */
    @Test
    void neovimsErrorIsTheCauseOfTheFailedQuestion() throws Exception {
        try (NeovimSession s = start()) {
            s.commandAsync("lua require('neovim4eclipse.quickfix').current = function() error('no list today') end");
            CompletableFuture<QuickfixEntries.Current> answer = s.currentQuickfixAsync();
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> answer.get(TestWait.millis(), TimeUnit.MILLISECONDS));
            assertInstanceOf(NeovimErrorException.class, e.getCause(), "Neovim's own error: " + e.getCause());
            assertTrue(e.getCause().getMessage().contains("no list today"), e.getCause().getMessage());
        }
    }
}
