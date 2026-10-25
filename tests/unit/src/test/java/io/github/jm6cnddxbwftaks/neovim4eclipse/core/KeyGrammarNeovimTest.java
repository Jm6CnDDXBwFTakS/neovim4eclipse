// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Mode;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.NeovimApi;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.KeyGrammar;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.KeyGrammar.Expect;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimModes;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * Compares {@link KeyGrammar} with a real Neovim: each sequence of the corpus is typed into Neovim
 * one key at a time, and what Neovim then expects (read from {@code nvim_get_mode}) must be what
 * the grammar says from the keys alone. This is the guard that the grammar matches Vim's: a
 * sequence the router misroutes becomes one more line in {@link #corpus()}.
 *
 * <p>How Neovim's answer maps to {@link Expect}: {@code blocking} (Neovim waits for one more key of
 * the command) is {@code ARGUMENT}; insert or replace mode is {@code TEXT}; operator-pending
 * ({@code no}) is {@code MOTION}; normal or visual mode is {@code COMMAND}. Where the grammar says
 * {@code MOTION}, the test also types {@code ?} and checks the {@code g??} rule: Neovim opens its
 * search line exactly when the grammar does not call the {@code ?} a doubled operator.
 *
 * <p>Integration profile only ({@code ./mvnw test -f tests/unit/pom.xml -Pintegration
 * -Dnvim.path=...}). One Neovim serves the whole corpus; it is reset between sequences.
 */
@Tag("integration")
class KeyGrammarNeovimTest {

    private static final String NVIM = TestNeovim.PATH;
    private static final List<String> TEXT = List.of(
            "say \"hello\" and 'bye' `tick` (paren) [x] now",
            "alpha beta gamma",
            "/* a comment */ end",
            "delta");

    private static NeovimConnection connection;
    private static NeovimApi api;

    @BeforeAll
    static void start() throws Exception {
        connection = NeovimConnection.open(NVIM, List.of("--clean", "-n"), "0.0.0");
        api = connection.api();
    }

    @AfterAll
    static void stop() {
        if (connection != null) {
            connection.requestQuit();
            connection.close();
        }
    }

    /**
     * The sequences, keys separated by spaces as the router sees them ({@code <lt>} is {@code <}).
     * Generated: every operator with every kind of continuation. Listed: the rest of the grammar.
     */
    static Set<String> corpus() {
        Set<String> corpus = new LinkedHashSet<>();
        String[] operators = { "d", "c", "y", ">", "<lt>", "=", "g u", "g U", "g ~", "g ?", "g q", "g w", "z f" }; // not g@: it needs an operatorfunc
        String[] continuations = { "", "i", "i \"", "a '", "i `", "i w", "a (", "t", "t x", "f \"", "g", "g g",
                "[", "2", "2 j", "v", "v j", "j" };
        for (String op : operators) {
            for (String rest : continuations) {
                corpus.add((op + " " + rest).strip());
            }
            String[] keys = op.split(" ");
            corpus.add(op + " " + keys[keys.length - 1]); // dd, gqq, g??, >>
            if (keys.length == 2) {
                corpus.add(op + " " + op); // gqgq, g?g?
            }
        }
        corpus.addAll(List.of(
                "!", // alone: with its motion, ! opens Neovim's command line (:.!), a mode not compared
                "g", "g g", "g J", "g ' a", "g '", "g `", "g r", "g r x", "g i", "g v", "g @",
                "z", "z z", "z t", "z f j",
                "[", "[ /", "] q", "[ [", "d [ /", "y ] '",
                "Z", "<C-w>", "<C-w> s", "<C-w> <C-w>",
                "f", "f :", "F x", "t /", "T x", "r", "r ?", "m", "m q", "'", "' a", "`", "` a",
                "\"", "\" a", "\" q y y", "2 \" a y y", "1 0 j", "d 1 0 j", "3 d d",
                "q", "q a", "q a j",
                "o", "O", "s", "S", "C", "R", "i", "a", "I", "A", "c c", "c i \"",
                "o x <Esc>", "c w x <Esc>",
                "v", "v i", "v i \"", "v a \"", "v i (", "v o", "v V", "v v", "v d", "v g ?",
                "v r", "v r x", "v c", "v q", "V", "V j", "V y", "<C-v>", "<C-v> i w", "<C-v> j", "v <Esc>"));
        return corpus;
    }

    @Test
    void theGrammarExpectsWhatNeovimExpects() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (String sequence : corpus()) {
            KeyGrammar grammar = new KeyGrammar();
            for (String key : sequence.split(" ")) {
                grammar.feed(key, false);
            }
            Mode neovim;
            try {
                neovim = typeFresh(sequence);
            } catch (Exception e) {
                throw new AssertionError("typing [" + sequence + "]", e);
            }
            Expect expected = expectOf(neovim, grammar.afterCount());
            if (expected == null) {
                wrong.add(sequence + ": Neovim is in mode " + neovim.mode() + " (no comparison possible)");
                continue;
            }
            if (expected != grammar.expect()) {
                wrong.add(sequence + ": Neovim " + neovim + " expects " + expected + ", the grammar " + grammar.expect());
                continue;
            }
            if (expected == Expect.MOTION) {
                boolean searchOpens = VimModes.isCmdline(typeFresh(sequence + " ?").mode());
                if (searchOpens == grammar.questionDoublesOperator()) {
                    wrong.add(sequence + " ?: Neovim " + (searchOpens ? "opens" : "does not open")
                            + " a search line, the grammar disagrees");
                }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.size() + " of " + corpus().size() + " sequences differ:\n"
                + String.join("\n", wrong));
    }

    /**
     * Neovim's state after a sequence, as the grammar's {@link Expect}; {@code null} for other modes.
     * After a count ({@code 2}, {@code d3}) Neovim reports {@code blocking} although the command is
     * still to come (it waits for more digits), so there the mode alone counts.
     */
    private static Expect expectOf(Mode mode, boolean afterCount) {
        if (mode.blocking() && !afterCount) {
            return Expect.ARGUMENT;
        }
        if (VimModes.isTyping(mode.mode())) {
            return Expect.TEXT;
        }
        if (VimModes.isOperatorPending(mode.mode())) {
            return Expect.MOTION;
        }
        if (mode.mode().startsWith("n") || VimModes.isVisual(mode.mode())) {
            return Expect.COMMAND;
        }
        return null; // command line, hit-enter prompt ...
    }

    /** Resets Neovim, then types {@code sequence} key by key and returns its mode after the last key. */
    private static Mode typeFresh(String sequence) throws Exception {
        reset();
        Mode mode = null;
        for (String key : sequence.split(" ")) {
            await(api.input(key));
            mode = settledMode();
        }
        return mode;
    }

    /**
     * Back to normal mode with the test text, the cursor at its start, no recording and no folds.
     * Esc and Ctrl-C end any half-typed command, insert or command line; {@code <C-\><C-n>} any
     * other mode.
     */
    private static void reset() throws Exception {
        for (int i = 0; ; i++) {
            await(api.input(i % 2 == 0 ? "<Esc>" : "<C-c><C-\\><C-n>"));
            Mode mode = settledMode();
            if ("n".equals(mode.mode()) && !mode.blocking()) {
                break; // idle: the requests below are answered, not queued behind a pending command
            }
            if (i == 20) {
                throw new IllegalStateException("Neovim does not return to normal mode: " + mode);
            }
        }
        Object recording = await(api.execLua("return vim.fn.reg_recording()", List.of()));
        if (recording != null && !recording.toString().isEmpty()) {
            await(api.input("q"));
            settledMode();
        }
        await(api.execLua("vim.cmd('silent! normal! zE') vim.api.nvim_buf_set_lines(0, 0, -1, false, {...})"
                + " vim.api.nvim_win_set_cursor(0, {1, 0})", TEXT));
    }

    /**
     * {@code nvim_get_mode} until two answers in a row agree: it is answered at once, while the keys
     * just sent may still be on their way through Neovim's input queue.
     */
    private static Mode settledMode() throws Exception {
        Mode previous = await(api.getMode());
        for (int i = 0; i < 20; i++) {
            Thread.sleep(2);
            Mode now = await(api.getMode());
            if (now.equals(previous)) {
                return now;
            }
            previous = now;
        }
        return previous;
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> future) throws Exception {
        return Rpc.await(future);
    }
}
