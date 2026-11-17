// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * The second Neovim of the Neovim view against a real {@code nvim --embed}: its screen, keys,
 * yanks, size and end, and the one rule (it never writes a file). Needs Neovim:
 * {@code -Pintegration -Dnvim.path=...}.
 */
@Tag("integration")
class ScreenSessionIntegrationTest {

    /** What a session reported, kept for the checks. */
    private static final class Recorder implements ScreenSession.Listener {
        final AtomicReference<GridSnapshot> screen = new AtomicReference<>();
        final List<List<Object>> yanks = new CopyOnWriteArrayList<>();
        final AtomicInteger ends = new AtomicInteger();

        @Override
        public void screen(GridSnapshot snapshot) {
            screen.set(snapshot);
        }

        @Override
        public void yank(String register, String type, List<String> lines) {
            yanks.add(List.of(register, type, lines));
        }

        @Override
        public void ended() {
            ends.incrementAndGet();
        }

        /** The screen's rows, joined with line breaks; "" before the first screen. */
        String text() {
            GridSnapshot s = screen.get();
            if (s == null) {
                return "";
            }
            StringBuilder rows = new StringBuilder();
            for (int r = 0; r < s.height(); r++) {
                rows.append(s.row(r)).append('\n');
            }
            return rows.toString();
        }
    }

    private final Recorder recorder = new Recorder();

    @BeforeEach
    void needsNeovim() {
        TestNeovim.require();
    }

    private ScreenSession start(List<String> profileArgs) throws Exception {
        return ScreenSession.start(TestNeovim.PATH, profileArgs, "0.0.0", 40, 10, recorder);
    }

    private static void waitFor(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + TestWait.millis();
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "timed out waiting for " + what);
    }

    /** Returns once the second Neovim has handled everything asked before (see {@code NeovimBarrier}). */
    private static void pass(ScreenSession s) throws Exception {
        s.execLuaAsync("return 0", List.of()).get(TestWait.millis(), TimeUnit.MILLISECONDS);
    }

    @Test
    void theWholeScreenIsDrawnAndFollowsTheText() throws Exception {
        try (ScreenSession s = start(List.of())) {
            waitFor(() -> recorder.screen.get() != null, "the first screen");
            assertEquals(40, recorder.screen.get().width());
            assertEquals(10, recorder.screen.get().height());

            s.execLuaAsync("vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'alpha', 'beta' })", List.of());
            waitFor(() -> recorder.text().startsWith("alpha"), "the text on the screen");
            assertTrue(recorder.text().contains("beta"), recorder.text());

            s.inputAsync(":echo 'from the view'<CR>"); // the : line and messages are part of the screen
            waitFor(() -> recorder.text().contains("from the view"), "the message on the last row");
        }
    }

    @Test
    void theScreenFollowsTheViewsSize() throws Exception {
        try (ScreenSession s = start(List.of())) {
            waitFor(() -> recorder.screen.get() != null, "the first screen");
            s.resizeAsync(60, 5);
            waitFor(() -> recorder.screen.get().width() == 60 && recorder.screen.get().height() == 5, "the new size");
        }
    }

    @Test
    void aYankReachesTheListenerWithItsRegister() throws Exception {
        try (ScreenSession s = start(List.of("-M"))) {
            s.execLuaAsync("vim.bo.modifiable = true; vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'OK one', 'two' });"
                    + " vim.bo.modifiable = false", List.of());
            s.inputAsync("gg\"ayy");
            waitFor(() -> !recorder.yanks.isEmpty(), "the yank");
            s.inputAsync("jyw");
            waitFor(() -> recorder.yanks.size() == 2, "the second yank");
            assertEquals(List.of("a", "V", List.of("OK one")), recorder.yanks.get(0));
            assertEquals(List.of("", "v", List.of("two")), recorder.yanks.get(1));
        }
    }

    @Test
    void nothingIsWrittenWhatTheUserTypes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("out.txt");
        try (ScreenSession s = start(List.of())) {
            s.execLuaAsync("vim.api.nvim_buf_set_lines(0, 0, -1, false, { 'text' })", List.of());
            s.inputAsync(":w " + file.toString().replace(" ", "\\ ") + "<CR>");
            s.inputAsync(":saveas " + dir.resolve("other.txt").toString().replace(" ", "\\ ") + "<CR>");
            // Two refusals, more lines than the message area: without ui2 (LuaScripts.SCREEN_SETUP)
            // Neovim would wait at a "Press ENTER" prompt here, and hold back this barrier's call.
            pass(s);
            waitFor(() -> recorder.text().contains("E142") || recorder.text().contains("'write'"),
                    "Neovim's refusal on the screen: " + recorder.text());
        }
        assertFalse(Files.exists(file), ":w wrote nothing");
        assertFalse(Files.exists(dir.resolve("other.txt")), ":saveas wrote nothing");
        try (var left = Files.list(dir)) {
            assertEquals(List.of(), left.toList(), "no swap file, no undo file either");
        }
    }

    @Test
    void aProfileThatWouldOpenAWayToWriteIsRefused() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> start(List.of("--cmd", "set write")));
        assertTrue(refused.getMessage().contains("write"), refused.getMessage());
    }

    @Test
    void quitInTheViewEndsTheSessionOnce() throws Exception {
        ScreenSession s = start(List.of("-M"));
        waitFor(() -> recorder.screen.get() != null, "the first screen");
        s.inputAsync(":q<CR>");
        waitFor(() -> recorder.ends.get() == 1, "the end");
        assertFalse(s.isAlive());
        s.close(); // after the end: nothing more happens
        s.inputAsync("x"); // dropped, no error
        assertEquals(1, recorder.ends.get());
    }

    @Test
    void closeEndsTheProcessAndTellsTheListener() throws Exception {
        ScreenSession s = start(List.of());
        s.close();
        waitFor(() -> recorder.ends.get() == 1, "the end");
        assertFalse(s.isAlive());
        assertThrows(Exception.class, () -> s.execLuaAsync("return 0", List.of()).get(TestWait.millis(), TimeUnit.MILLISECONDS));
    }
}
