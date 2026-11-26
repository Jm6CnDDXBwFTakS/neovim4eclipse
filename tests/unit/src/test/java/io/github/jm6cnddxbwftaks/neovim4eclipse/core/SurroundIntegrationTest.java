// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.PinnedPlugins;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InsertReplay;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * vim-surround and vim-repeat through the session, at the commits pinned and vetted in
 * {@code releng/test-plugins.properties}: a plug-in that works on text, as the README says such
 * plug-ins work, as in a terminal. What it asks of the session: its operator waits for a motion
 * and then a character ({@code ysiw"}), Neovim answering no request meanwhile; a tag is typed at
 * its prompt ({@code ysiw<em>}); {@code .} repeats it through vim-repeat's mapping; its Visual
 * {@code S}; and its {@code Ctrl-G s} in an insert Eclipse types, where Neovim waits for the
 * character after the sync point's key. Each change reaches the session's listeners, so the
 * editor's text is Neovim's. The same with keys on a real editor is {@code SurroundEndToEndTest}.
 * Skipped without the plug-ins ({@link PinnedPlugins#HOW}).
 *
 * <p>Each test has a Neovim of its own: the plug-in's mappings are the whole Neovim's.
 */
@Tag("integration")
class SurroundIntegrationTest {

    @Test
    void theOperatorsTheirRepeatAndTheTagPromptChangeTheText() throws Exception {
        try (NeovimSession s = start()) {
            s.setBufferLines(List.of("alpha beta gamma", "delta epsilon", "zeta eta"));
            keys(s, "g", "g", "0");

            keys(s, "y", "s", "i", "w", "\"");
            waitForLine(s, 1, "\"alpha\" beta gamma", "ysiw\" surrounds the word");
            keys(s, "f", "b", ".");
            waitForLine(s, 1, "\"alpha\" \"beta\" gamma", ". repeats it through vim-repeat");

            keys(s, "c", "s", "\"", "'");
            waitForLine(s, 1, "\"alpha\" 'beta' gamma", "cs\"' changes the quotes around the cursor");
            keys(s, "0", ".");
            waitForLine(s, 1, "'alpha' 'beta' gamma", ". repeats cs");
            keys(s, "d", "s", "'");
            waitForLine(s, 1, "alpha 'beta' gamma", "ds' deletes them");

            keys(s, "j", "0", "v", "i", "w", "S", ")");
            waitForLine(s, 2, "(delta) epsilon", "Visual S surrounds the selection");
            waitFor(() -> "n".equals(s.getMode()), "back in Normal mode", () -> s.getMode());

            // < in Neovim's key notation, as the editor sends it: a bare < starts a key's name.
            keys(s, "j", "0", "y", "s", "i", "w", "<lt>", "e", "m", ">");
            waitForLine(s, 3, "<em>zeta</em> eta", "a tag typed at the prompt, ended by >");
            keys(s, "y", "s", "s", "]");
            waitForLine(s, 3, "[<em>zeta</em> eta]", "yss] surrounds the line");
            waitFor(() -> "n".equals(s.getMode()), "Normal mode", () -> s.getMode());
        }
    }

    /**
     * {@code x} typed by Eclipse, then {@code Ctrl-G}: a sync point (docs/KEYBOARD.md), and
     * vim-surround's Insert-mode mapping {@code <C-G>s} waits for its {@code s}, then for the
     * character, Neovim driving the insert meanwhile. {@code "} puts in a pair with the cursor
     * between; the {@code y} sent next goes in there, and {@code Esc} ends the insert. Neovim's
     * buffer is what is checked: the keys go to Neovim here, so the hand-back to Eclipse's typing
     * after the pair is the UI test's ({@code SurroundEndToEndTest}).
     */
    @Test
    void ctrlGSInAnInsertEclipseTypesPutsInAPair() throws Exception {
        try (NeovimSession s = start()) {
            List<String> before = List.of("alpha", "");
            s.setBufferLines(before);
            keys(s, "G", "i");
            assertFalse(s.isNeovimDrivenInsert(), "Eclipse types this insert");
            List<String> document = new ArrayList<>(before);
            document.set(1, "x");
            s.setLeaveInsertPush(() -> {
                try {
                    s.setBufferLines(document);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            s.syncInsertThenInputAsync(InsertReplay.syncPoint("alpha\n\n", "alpha\nx\n", 6, 7), "<C-g>");
            waitFor(s::isNeovimDrivenInsert, "Neovim driving the insert while the mapping waits", () -> s.getMode());
            s.inputAsync("s");
            s.inputAsync("\"");
            waitForLine(s, 2, "x\"\"", "a pair put in after the x");
            s.inputAsync("y");
            waitForLine(s, 2, "x\"y\"", "the y between the quotes");
            s.inputAsync("<Esc>");
            waitFor(() -> "n".equals(s.getMode()) && !s.isNeovimDrivenInsert(), "the insert over", () -> s.getMode());
            assertEquals(List.of("alpha", "x\"y\""), s.getBufferLines());
        }
    }

    /**
     * A session with both plug-ins loaded, whose buffer changes are applied as the editor applies
     * them (each change's {@code afterApply} runs, which lets Eclipse type again after a sync
     * point's key), or skipped without the plug-ins.
     */
    private static NeovimSession start() throws Exception {
        TestNeovim.require();
        Optional<Path> surround = PinnedPlugins.find("vim-surround");
        Optional<Path> repeat = PinnedPlugins.find("vim-repeat");
        assumeTrue(surround.isPresent() && repeat.isPresent(),
                "vim-surround and vim-repeat are not fetched at their pinned commits: " + PinnedPlugins.HOW);
        NeovimSession s = NeovimSession.start(TestNeovim.PATH, List.of("--clean"));
        try {
            s.addBufferChangeListener(change -> {
                if (change.afterApply() != null) {
                    change.afterApply().run();
                }
            });
            // y, c and d in Normal mode and Ctrl-G in Insert mode each begin a longer mapping (ys,
            // cs, ds, <C-G>s): Neovim waits 'timeoutlen' for the next key, then gives up and runs
            // its own, and on a loaded machine two keys can be more than the default 1 s apart.
            s.lua("vim.o.timeoutlen = " + TestWait.mappingMillis() + " return 0");
            s.lua(PinnedPlugins.loadSurround(surround.get(), repeat.get()) + " return 0");
            return s;
        } catch (Throwable t) { // the session is not the caller's yet
            s.close();
            throw t;
        }
    }

    /** Types each key as the editor's Normal-mode keys are, one at a time. */
    private static void keys(NeovimSession s, String... keys) throws Exception {
        for (String key : keys) {
            s.input(key);
        }
    }

    private static void waitForLine(NeovimSession s, int line1, String text, String what) throws Exception {
        waitFor(() -> text.equals(s.getBufferLines().get(line1 - 1)), what, () -> {
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
