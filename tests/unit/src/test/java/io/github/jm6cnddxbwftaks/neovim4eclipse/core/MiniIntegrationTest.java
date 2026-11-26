// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.PinnedPlugins;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * mini.nvim's mini.pairs and mini.ai through the session, at the release pinned and vetted in
 * {@code releng/test-plugins.properties}. mini.pairs maps keys in Insert mode, so it acts in an
 * insert Neovim types: one recorded into a macro, where every key reaches Neovim. There {@code (}
 * puts in {@code ()}, the letter typed goes between, Backspace takes the letter and then the pair,
 * and {@code "} puts in {@code ""}. mini.ai's own text objects, which Neovim does not have: an
 * argument ({@code cia}), any quote ({@code diq}), a function call over two lines ({@code daf}),
 * each from before the object (Neovim's own {@code ci(} jumps ahead too, so it would show nothing
 * of mini.ai).
 * Each change reaches the session's listeners, so the editor's text is Neovim's. An insert Eclipse
 * types, which mini.pairs never sees, is {@code MiniEndToEndTest}'s. Skipped without the plug-in
 * ({@link PinnedPlugins#HOW}).
 *
 * <p>Each test has a Neovim of its own: the modules' mappings are the whole Neovim's.
 */
@Tag("integration")
class MiniIntegrationTest {

    @Test
    void miniPairsClosesAndDeletesPairsInAnInsertNeovimTypes() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("one"));
            keys(s, "q", "q", "o");
            waitFor(() -> "i".equals(s.getMode()) && s.isNeovimDrivenInsert(),
                    "an insert Neovim types, recorded", () -> s.getMode());

            keys(s, "(");
            waitForLines(s, List.of("one", "()"), "( put in ()");
            keys(s, "y");
            waitForLines(s, List.of("one", "(y)"), "the y between the brackets");
            keys(s, "<BS>");
            waitForLines(s, List.of("one", "()"), "Backspace took the y");
            keys(s, "<BS>");
            waitForLines(s, List.of("one", ""), "Backspace took the pair");
            keys(s, "\"");
            waitForLines(s, List.of("one", "\"\""), "\" put in \"\"");

            keys(s, "<Esc>", "q");
            waitFor(() -> "n".equals(s.getMode()) && !s.isNeovimDrivenInsert(), "the insert and the recording over",
                    () -> s.getMode());
            assertEquals(List.of("one", "\"\""), s.getBufferLines(), "the text as typed");
        }
    }

    @Test
    void miniAisOwnTextObjectsChangeTheText() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("foo(bar, baz) end", "say x 'hi there' now", "x = f(1,", "  2) + 3"));
            keys(s, "g", "g", "0", "c", "i", "a", "<Esc>");
            waitForLines(s, List.of("foo(, baz) end", "say x 'hi there' now", "x = f(1,", "  2) + 3"),
                    "cia from before the bracket emptied the first argument");
            keys(s, "j", "0", "d", "i", "q");
            waitForLines(s, List.of("foo(, baz) end", "say x '' now", "x = f(1,", "  2) + 3"),
                    "diq from before the quotes emptied them");
            keys(s, "j", "0", "f", "f", "d", "a", "f");
            waitForLines(s, List.of("foo(, baz) end", "say x '' now", "x =  + 3"), "daf took the call over two lines");
            waitFor(() -> "n".equals(s.getMode()), "Normal mode", () -> s.getMode());
        }
    }

    /**
     * A session with the two modules set up, whose buffer changes are applied as the editor
     * applies them (each change's {@code afterApply} runs), or skipped without the plug-in.
     */
    private static NeovimSession start() throws Exception {
        TestNeovim.require();
        Optional<Path> mini = PinnedPlugins.find("mini.nvim");
        assumeTrue(mini.isPresent(), "mini.nvim is not fetched at its pinned commit: " + PinnedPlugins.HOW);
        NeovimSession s = NeovimSession.start(TestNeovim.PATH, List.of("--clean"));
        try {
            s.addBufferChangeListener(change -> {
                if (change.afterApply() != null) {
                    change.afterApply().run();
                }
            });
            s.lua(PinnedPlugins.loadMini(mini.get()) + " return 0");
            return s;
        } catch (Throwable t) { // the session is not the caller's yet
            s.close();
            throw t;
        }
    }

    /** Types each key as the editor's keys reach Neovim, one at a time. */
    private static void keys(NeovimSession s, String... keys) throws Exception {
        for (String key : keys) {
            s.input(key);
        }
    }

    private static void waitForLines(NeovimSession s, List<String> lines, String what) throws Exception {
        waitFor(() -> lines.equals(s.getBufferLines()), what, () -> {
            try {
                return "lines " + s.getBufferLines() + ", mode " + s.getMode();
            } catch (Exception e) {
                return "Neovim did not answer: " + e;
            }
        });
    }

    private interface Check {
        boolean holds() throws Exception;
    }

    /** Waits for Neovim's or the session's state, saying {@code state} when it times out. */
    private static void waitFor(Check condition, String what, Supplier<String> state) throws Exception {
        BooleanSupplier quiet = () -> {
            try {
                return condition.holds();
            } catch (Exception e) {
                return false;
            }
        };
        long deadline = System.currentTimeMillis() + TestWait.millis();
        while (!quiet.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what + "; " + state.get());
            }
            Thread.sleep(25);
        }
    }
}
