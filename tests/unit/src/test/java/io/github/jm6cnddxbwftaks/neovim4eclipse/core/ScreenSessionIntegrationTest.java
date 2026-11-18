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
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridEvent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HelpPage;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.MessageEvent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewText;

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
        final AtomicInteger toEditor = new AtomicInteger();

        @Override
        public void screen(GridSnapshot snapshot) {
            screen.set(snapshot);
        }

        @Override
        public void yank(String register, String type, List<String> lines) {
            yanks.add(List.of(register, type, lines));
        }

        @Override
        public void toEditor() {
            toEditor.incrementAndGet();
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

    /** The folder the second Neovim starts in, as the view's own would be. */
    @TempDir
    Path folder;

    private ScreenSession start(List<String> profileArgs) throws Exception {
        return ScreenSession.start(TestNeovim.PATH, profileArgs, "0.0.0", folder, 40, 10, recorder);
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

    /**
     * The output profile: the text replaces what was shown, an error line takes Neovim's
     * {@code ErrorMsg} color, {@code :messages} puts the cursor on the newest line, and {@code q}
     * closes it all. Nothing can be typed into the text ({@code -M}).
     */
    @Test
    void theOutputProfileShowsTextWithItsErrorsInColor() throws Exception {
        try (ScreenSession s = start(OutputProfile.ARGS)) {
            ViewText messages = ViewText.messages(new MessageEvent.History(List.of(
                    new MessageEvent.History.Entry("", "plain line"),
                    new MessageEvent.History.Entry("emsg", "E486: Pattern not found: x"))));
            assertEquals(2L, ((Number) OutputProfile.show(s, messages, true)
                    .get(TestWait.millis(), TimeUnit.MILLISECONDS)).longValue());
            waitFor(() -> recorder.text().startsWith("plain line"), "the text: " + recorder.text());
            waitFor(() -> recorder.text().contains(" messages "), "the status line naming the view's text: " + recorder.text());
            assertFalse(recorder.text().contains("[Scratch]"), recorder.text());
            GridSnapshot screen = recorder.screen.get();
            assertEquals(1, screen.cursorRow(), "the newest message, last");
            int plain = screen.attr(screen.hlAt(0, 0)).foreground();
            int error = screen.attr(screen.hlAt(1, 0)).foreground();
            assertTrue(error != GridEvent.NO_COLOR && error != plain, "ErrorMsg's color on the error line");

            s.inputAsync("ggiinserted<Esc>");
            pass(s);
            assertTrue(recorder.text().startsWith("plain line"), "-M: nothing typed into the text: " + recorder.text());

            OutputProfile.show(s, ViewText.checkhealth(List.of("vim.health: OK", "- OK fine")), true);
            waitFor(() -> recorder.text().startsWith("vim.health"), "the report replacing the messages");
            assertEquals(0, recorder.screen.get().cursorRow(), "a report starts at its top");
            assertFalse(recorder.text().contains("plain line"), recorder.text());

            s.inputAsync("q");
            waitFor(() -> recorder.ends.get() == 1, "q closing it");
        }
    }

    @Test
    void ctrlWBackToTheEditor() throws Exception {
        try (ScreenSession s = start(OutputProfile.ARGS)) {
            s.inputAsync("<C-w>p");
            s.inputAsync("<C-w><C-w>");
            s.inputAsync("<C-w>k");
            waitFor(() -> recorder.toEditor.get() == 3, "each key asking for the editor");
        }
    }

    /** The second Neovim starts in the folder it is given, never in Eclipse's. */
    @Test
    void theSecondNeovimStartsInItsOwnFolder() throws Exception {
        try (ScreenSession s = start(OutputProfile.ARGS)) {
            Object cwd = s.execLuaAsync("return vim.fn.getcwd()", List.of()).get(TestWait.millis(), TimeUnit.MILLISECONDS);
            assertEquals(folder.toRealPath(), Path.of(String.valueOf(cwd)).toRealPath());
        }
    }

    /** A plug-in of the user's, for the help profile: one help page, and a help script that leaves a mark. */
    private static Path helpPlugin(Path root) throws Exception {
        Path doc = Files.createDirectories(root.resolve("plug/doc"));
        Files.writeString(doc.resolve("probe.txt"), String.join("\n", "*probe.txt*  A probe", "", "*probe-topic*",
                "See |probe-other|.", "", "*probe-other*", "other", "", " vim:tw=78:ts=8:ft=help:norl:", ""));
        Files.writeString(doc.resolve("tags"), "probe-other\tprobe.txt\t/*probe-other*\n"
                + "probe-topic\tprobe.txt\t/*probe-topic*\nprobe.txt\tprobe.txt\t/*probe.txt*\n");
        Path ftplugin = Files.createDirectories(root.resolve("plug/ftplugin"));
        Files.writeString(ftplugin.resolve("help.lua"), "vim.g.probe_help_script = (vim.g.probe_help_script or 0) + 1\n");
        return root.resolve("plug");
    }

    private static Object lua(ScreenSession s, String code) throws Exception {
        return s.execLuaAsync(code, List.of()).get(TestWait.millis(), TimeUnit.MILLISECONDS);
    }

    /**
     * The help profile: the page a plug-in of the user's has, found on the editing Neovim's
     * {@code 'runtimepath'}, opened as Neovim's own help (a help buffer, its script run), the whole
     * screen, the cursor on the line the editing Neovim had. A file opened from it is swapped back
     * for the page; {@code q} closes it all.
     */
    @Test
    void theHelpProfileOpensTheUsersPageWithItsHelpScript(@TempDir Path user) throws Exception {
        Path plugin = helpPlugin(user);
        Path other = Files.writeString(user.resolve("notes.txt"), "not help\n");
        try (ScreenSession s = start(HelpProfile.ARGS)) {
            String runtimepath = lua(s, "return vim.o.runtimepath") + "," + plugin;
            assertEquals("", HelpProfile.show(s, new HelpPage("probe-other", "probe.txt", 7, runtimepath), true)
                    .get(TestWait.millis(), TimeUnit.MILLISECONDS));
            assertEquals("help", lua(s, "return vim.bo.buftype"));
            assertEquals(7L, ((Number) lua(s, "return vim.fn.line('.')")).longValue(), "the line it had");
            assertEquals(1L, ((Number) lua(s, "return #vim.api.nvim_tabpage_list_wins(0)")).longValue(), "the whole screen");
            assertEquals(1L, ((Number) lua(s, "return vim.g.probe_help_script")).longValue(), "the plug-in's help script ran");
            waitFor(() -> recorder.text().startsWith("other") && recorder.text().contains("[Help]"),
                    "the page from its line on, under Neovim's help status line: " + recorder.text());

            s.inputAsync(":edit " + other.toString().replace("\\", "/") + "<CR>");
            pass(s);
            waitFor(() -> {
                try {
                    return "help".equals(lua(s, "return vim.bo.buftype"));
                } catch (Exception e) {
                    return false;
                }
            }, "the page back after :edit");
            assertTrue(String.valueOf(lua(s, "return vim.fn.execute('messages')")).contains("shows help only"));

            s.inputAsync("q");
            waitFor(() -> recorder.ends.get() == 1, "q closing it");
        }
    }

    /** A tag the second Neovim does not find, in a file it does: the file opens, on the line. */
    @Test
    void anUnknownTagFallsBackToTheFile(@TempDir Path user) throws Exception {
        Path plugin = helpPlugin(user);
        try (ScreenSession s = start(HelpProfile.ARGS)) {
            String runtimepath = lua(s, "return vim.o.runtimepath") + "," + plugin;
            assertEquals("", HelpProfile.show(s, new HelpPage("not-a-tag", "probe.txt", 7, runtimepath), false)
                    .get(TestWait.millis(), TimeUnit.MILLISECONDS));
            assertEquals("probe.txt", lua(s, "return vim.fn.expand('%:t')"));
            assertEquals(7L, ((Number) lua(s, "return vim.fn.line('.')")).longValue());
        }
    }

    /**
     * Neither the tag nor the file found: Neovim's error, shown on its message line and answered
     * for the log, and {@code q} still closes the view.
     */
    @Test
    void anUnknownTagAndFileShowNeovimsError() throws Exception {
        try (ScreenSession s = start(HelpProfile.ARGS)) {
            String runtimepath = String.valueOf(lua(s, "return vim.o.runtimepath"));
            Object answer = HelpProfile.show(s, new HelpPage("no-such-tag-anywhere", "no-such-file-anywhere.txt", 1, runtimepath), false)
                    .get(TestWait.millis(), TimeUnit.MILLISECONDS);
            assertTrue(String.valueOf(answer).contains("E149"), String.valueOf(answer));
            waitFor(() -> recorder.text().contains("E149"), "the error on the screen: " + recorder.text());
            s.inputAsync("q");
            waitFor(() -> recorder.ends.get() == 1, "q closing it");
        }
    }

    /**
     * A {@code :helpgrep} entry: the view runs the same {@code :helpgrep} and goes to the entry, so
     * {@code ]q} goes on through the matches there, and {@code :copen} shows the list beside the
     * page. The next entry handed over reuses that list rather than searching again.
     */
    @Test
    void aHelpgrepEntryRunsTheSameHelpgrepThere() throws Exception {
        try (ScreenSession s = start(HelpProfile.ARGS)) {
            String runtimepath = String.valueOf(lua(s, "return vim.o.runtimepath"));
            // Where the editing Neovim's third match would be: the same help files, the same order.
            String third = String.valueOf(lua(s, "vim.cmd('silent helpgrep runtimepath') "
                    + "local e = vim.fn.getqflist()[3] local f = vim.fn.fnamemodify(vim.fn.bufname(e.bufnr), ':t') "
                    + "vim.fn.setqflist({}, 'f') return f .. ':' .. e.lnum"));
            String file = third.substring(0, third.indexOf(':'));
            int line = Integer.parseInt(third.substring(third.indexOf(':') + 1));
            assertEquals("", HelpProfile.show(s, new HelpPage(file, file, line, runtimepath, "runtimepath", 3), false)
                    .get(TestWait.millis(), TimeUnit.MILLISECONDS));
            assertEquals(3L, ((Number) lua(s, "return vim.fn.getqflist({ idx = 0 }).idx")).longValue());
            assertEquals((long) line, ((Number) lua(s, "return vim.fn.line('.')")).longValue());
            lua(s, "vim.g.probe_list = vim.fn.getqflist({ id = 0 }).id return 0");

            s.inputAsync("]q");
            pass(s);
            assertEquals(4L, ((Number) lua(s, "return vim.fn.getqflist({ idx = 0 }).idx")).longValue(), "]q went on");
            assertEquals("help", lua(s, "return vim.bo.buftype"));

            HelpProfile.show(s, new HelpPage(file, file, line, runtimepath, "runtimepath", 3), false)
                    .get(TestWait.millis(), TimeUnit.MILLISECONDS);
            assertEquals(true, lua(s, "return vim.fn.getqflist({ id = 0 }).id == vim.g.probe_list"), "the same list, not a new search");

            s.inputAsync(":copen<CR>");
            pass(s);
            assertEquals(true, lua(s, "for _, w in ipairs(vim.api.nvim_list_wins()) do "
                    + "if vim.fn.win_gettype(w) == 'quickfix' then return true end end return false"), "the list stays open");
        }
    }
}
